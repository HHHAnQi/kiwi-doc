package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * ChatService 单元测试(V2-B 升级版)。
 *
 * <p>覆盖五个关键路径: EMPTY_KB / NO_RECALL / OK / LLM_DEGRADED / DOC_NOT_READY+DOC_NOT_FOUND。 召回与 LLM 全部走
 * mock(不依赖 Milvus / DashScope 实体服务)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private ChatMessages chatMessages;
    // V2-B 新增
    @Mock private RetrieveService retrieveService;
    @Mock private ChatClient chatClient;
    // V3-W3 Langfuse trace 接入(DoD-5); mock 让所有 trace 调用成 no-op
    @Mock private TraceObserver traceObserver;
    // Phase 3.A: metrics mock 成 void no-op, ChatService recordChatTotal 等不抛 NPE。
    // 架构债清理: 改用 MetricsPort 接口; RagdocMetrics 实现该接口, 在 @Mock 时仅声明接口类型,
    // ArchUnit 不再判 test 跨层引用 infrastructure。
    @Mock private com.xxx.ragdoc.application.metrics.MetricsPort metrics;

    // Phase 1 / C4: 多轮对话 3 件 optional bean, @InjectMocks 自动注入到 setConversationDeps
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.ConversationStore conversationStore;
    // 架构债清理: mock port 接口而非 infrastructure 实现类, 让 application test 不依赖 infrastructure
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort queryContextualizer;
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.PromptAssemblerPort promptAssembler;

    @InjectMocks private ChatService chatService;

    private static final TraceId TID = new TraceId("a1b2c3d4");

    @BeforeEach
    void setupMessages() {
        when(chatMessages.getEmptyKbMessage()).thenReturn("EMPTY_MSG");
        when(chatMessages.getNoRecallMessage()).thenReturn("NORECALL_MSG");
        when(chatMessages.getLlmDegradedMessage()).thenReturn("LLM_FAIL:");
        // 默认召回为空, NO_RECALL 分支复用
        when(retrieveService.retrieve(any())).thenReturn(RetrieveService.RetrieveResult.empty());
        // traceObserver.startTrace 默认返回原 traceId 让内部链路对齐
        lenient().when(traceObserver.startTrace(any(), any(), any())).thenReturn(TID.value());
        // Phase 1 / C4: @InjectMocks 对 setter 注入 @Autowired(required=false) 不一定可靠,
        // 显式手动调 setter 强制注入 mock (mock 都是 @Mock 创建, 非 null, isMultiTurnEnabled() 应 true)
        chatService.setConversationDeps(conversationStore, queryContextualizer, promptAssembler);
    }

    @Nested
    @DisplayName("EMPTY_KB: 0 个 READY 文档")
    class EmptyKb {
        @Test
        @DisplayName("应返回 state_hint=EMPTY_KB + 友好文案 + 空 citations")
        void shouldReturnEmptyKb() {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(0L);

            ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.EMPTY_KB);
            assertThat(r.answer()).isEqualTo("EMPTY_MSG");
            assertThat(r.citations()).isEmpty();
        }

        @Test
        @DisplayName("必须落 chat_traces 记录(state_hint=EMPTY_KB)")
        void shouldWriteChatTrace() {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(0L);

            chatService.chat(new ChatCommand("hello", null, 5), TID);

            ArgumentCaptor<ChatTrace> captor = ArgumentCaptor.forClass(ChatTrace.class);
            verify(chatTracesRepository).save(captor.capture());
            ChatTrace saved = captor.getValue();
            assertThat(saved.traceId()).isEqualTo(TID);
            assertThat(saved.stateHint()).isEqualTo(StateHint.EMPTY_KB);
            assertThat(saved.queryHash()).matches("^[a-fA-F0-9]{64}$");
            assertThat(saved.queryLen()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("NO_RECALL: ≥1 READY 文档但召回为空")
    class NoRecall {
        @Test
        @DisplayName("应返回 state_hint=NO_RECALL")
        void shouldReturnNoRecall() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(3L);
            when(retrieveService.retrieve(any()))
                    .thenReturn(RetrieveService.RetrieveResult.empty());

            ChatResult r = chatService.chat(new ChatCommand("Sentinel 限流", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
            assertThat(r.answer()).isEqualTo("NORECALL_MSG");
            assertThat(r.citations()).isEmpty();
            // 召回被调用过
            verify(retrieveService, times(1)).retrieve(any());
            // LLM 不应被调用(召回空时不调 LLM, 节省成本)
            verify(chatClient, never()).chat(any(), any());
        }
    }

    @Nested
    @DisplayName("OK: 召回成功 + LLM 成功")
    class OkPath {
        @Test
        @DisplayName("应返回 state_hint=OK + LLM 答案 + citations")
        void shouldReturnOkWithAnswerAndCitations() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(1L);
            // 召回 2 条 chunk
            when(retrieveService.retrieve(any()))
                    .thenReturn(
                            new RetrieveService.RetrieveResult(
                                    List.of(
                                            new RetrieveService.Citation(
                                                    19L,
                                                    6L,
                                                    0,
                                                    "Sentinel 文本",
                                                    "Sentinel 文本",
                                                    0.9f,
                                                    java.util.List.of()),
                                            new RetrieveService.Citation(
                                                    20L,
                                                    6L,
                                                    0,
                                                    "Nacos 文本",
                                                    "Nacos 文本",
                                                    0.8f,
                                                    java.util.List.of())),
                                    "not_enabled",
                                    0.8f,
                                    0f));
            when(chatClient.chat(any(), any())).thenReturn("限流策略用 Sentinel[1]");

            ChatResult r = chatService.chat(new ChatCommand("怎么限流", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.answer()).isEqualTo("限流策略用 Sentinel[1]");
            assertThat(r.citations()).hasSize(2);
            assertThat(r.citations().get(0).chunkId()).isEqualTo(19L);
            assertThat(r.citations().get(0).snippet()).isEqualTo("Sentinel 文本");
        }
    }

    @Nested
    @DisplayName("LLM_DEGRADED: 召回成功但 LLM 抛异常")
    class LlmDegraded {
        @Test
        @DisplayName("应返回 state_hint=LLM_DEGRADED + trace_id 兜底 + citations 仍返回")
        void shouldDegradeWhenLlmThrows() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(1L);
            when(retrieveService.retrieve(any()))
                    .thenReturn(
                            new RetrieveService.RetrieveResult(
                                    List.of(
                                            new RetrieveService.Citation(
                                                    19L,
                                                    6L,
                                                    0,
                                                    "片段",
                                                    "片段",
                                                    0.9f,
                                                    java.util.List.of())),
                                    "not_enabled",
                                    0.9f,
                                    0f));
            when(chatClient.chat(any(), any()))
                    .thenThrow(new RuntimeException("DashScope timeout"));

            ChatResult r = chatService.chat(new ChatCommand("怎么限流", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.LLM_DEGRADED);
            // 兜底文案 + trace_id(LlmDegradedMessage="LLM_FAIL:" + tid)
            assertThat(r.answer()).startsWith("LLM_FAIL:").endsWith(TID.value());
            // 虽 LLM 失败, citations 仍返回(用户可看检索到的片段)
            assertThat(r.citations()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("限制 doc_id 校验")
    class DocIdValidation {
        @Test
        @DisplayName("doc_id 不存在 → 404 DOC_NOT_FOUND")
        void shouldRejectNonExistentDoc() {
            when(documentRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.chat(new ChatCommand("q", 999L, 5), TID))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((NotFoundException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FOUND));
        }

        @Test
        @DisplayName("doc 状态=PARSING → 409 DOC_NOT_READY")
        void shouldRejectNotReadyDoc() {
            Document doc =
                    Document.restore(
                            new DocumentId(1L),
                            new ContentHash(
                                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                            "f.pdf",
                            "application/pdf",
                            1L,
                            "default",
                            DocumentStatus.PARSING,
                            0,
                            null,
                            java.util.List.of(),
                            false);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> chatService.chat(new ChatCommand("q", 1L, 5), TID))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_READY));
        }

        @Test
        @DisplayName("非 READY 文档时, 不应写 chat_traces")
        void shouldNotWriteTraceOnRejection() {
            Document doc =
                    Document.restore(
                            new DocumentId(1L),
                            new ContentHash(
                                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                            "f.pdf",
                            "application/pdf",
                            1L,
                            "default",
                            DocumentStatus.PARSING,
                            0,
                            null,
                            java.util.List.of(),
                            false);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            try {
                chatService.chat(new ChatCommand("q", 1L, 5), TID);
            } catch (Exception ignored) {
                // 期望抛异常
            }
            verify(chatTracesRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("响应含 trace_id(供前端反馈关联)")
    void responseShouldCarryTraceId() {
        when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(0L);

        ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

        assertThat(r.traceId()).isEqualTo(TID);
    }

    /**
     * Phase 1 / C4 (ADR-0011): 多轮对话启用时的行为集成测试 (单测级别, 跑 mock 不跑真 LLM)。
     *
     * <p>covers:
     *
     * <ul>
     *   <li>ConversationStore.findById 返回 ctx → queryContextualizer 被调
     *   <li>rewrite 成功 → retrieve 收到 rewritten query (不是原 cmd.query)
     *   <li>LLM 成功 → conversationsStore.save 被调用 (history 写回)
     *   <li>LLM 失败 (DEGRADED) → conversationsStore.save 不被调 (抗污染硬 gate G3)
     * </ul>
     */
    @Nested
    @DisplayName("多轮对话 C4 测试")
    class MultiTurn {

        private static final String CONV_ID = "conv-test-123";

        @Test
        @DisplayName("有 ctx 启用时应调 queryContextualizer + 用 rewritten query 喂 retrieve")
        void chatWithCtx_shouldCallRewriterAndRetrieveWithRewrittenQuery() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(1L);
            // 1. store 返回 ctx with 1 turn
            ConversationContext ctx =
                    ConversationContext.empty(CONV_ID)
                            .appendTurn(
                                    new ConversationContext.Turn(
                                            "Sentinel?", "10", List.of(1L), StateHint.OK,
                                            java.time.Instant.now()));
            when(conversationStore.findById(CONV_ID)).thenReturn(java.util.Optional.of(ctx));
            // 2. rewriter 返回 ok, rewritten query
            when(queryContextualizer.contextualize(any(), any()))
                    .thenReturn(
                            com.xxx.ragdoc.application.chat.conversation.ContextualizeResult
                                    .success("那 Hystrix 呢", "Hystrix 默认 QPS", 50));
            // 3. retrieve 返回 1 chunk
            when(retrieveService.retrieve(any()))
                    .thenReturn(
                            new RetrieveService.RetrieveResult(
                                    List.of(
                                            new RetrieveService.Citation(
                                                    19L, 6L, 0,
                                                    "Hystrix 文本", "Hystrix 文本", 0.9f,
                                                    List.of())),
                                    "not_enabled", 0.9f, 0f));
            when(chatClient.chat(any(), any())).thenReturn("Hystrix 默认 10");
            when(promptAssembler.buildHistoryBlock(any(), anyBoolean()))
                    .thenReturn("[最近对话] Q: Sentinel? A: 10");
            ArgumentCaptor<ConversationContext> ctxCaptor =
                    ArgumentCaptor.forClass(ConversationContext.class);

            ChatResult r =
                    chatService.chat(
                            new ChatCommand("那 Hystrix 呢", null, 5), TID, CONV_ID);

            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            // verify retrieve 收到 rewritten query, 不是原 query
            ArgumentCaptor<ChatCommand> cmdCaptor =
                    ArgumentCaptor.forClass(ChatCommand.class);
            verify(retrieveService).retrieve(cmdCaptor.capture());
            assertThat(cmdCaptor.getValue().query()).isEqualTo("Hystrix 默认 QPS");
            // verify history 写回 — 新 ctx 含 2 turns
            verify(conversationStore).save(ctxCaptor.capture());
            assertThat(ctxCaptor.getValue().recentTurns()).hasSize(2);
            assertThat(ctxCaptor.getValue().recentTurns().get(1).userQuery())
                    .isEqualTo("那 Hystrix 呢");
        }

        @Test
        @DisplayName("LLM_DEGRADED 时不应写回 history (防污染硬 gate G3)")
        void llmDegraded_shouldNotSaveHistory() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(1L);
            ConversationContext ctx =
                    ConversationContext.empty(CONV_ID)
                            .appendTurn(
                                    new ConversationContext.Turn(
                                            "Sentinel?", "10", List.of(1L), StateHint.OK,
                                            java.time.Instant.now()));
            when(conversationStore.findById(CONV_ID)).thenReturn(java.util.Optional.of(ctx));
            when(queryContextualizer.contextualize(any(), any()))
                    .thenReturn(
                            com.xxx.ragdoc.application.chat.conversation.ContextualizeResult
                                    .success("那 Hystrix 呢", "Hystrix 默认 QPS", 50));
            when(retrieveService.retrieve(any()))
                    .thenReturn(
                            new RetrieveService.RetrieveResult(
                                    List.of(
                                            new RetrieveService.Citation(
                                                    19L, 6L, 0,
                                                    "Hystrix 文本", "Hystrix 文本", 0.9f,
                                                    List.of())),
                                    "not_enabled", 0.9f, 0f));
            when(chatClient.chat(any(), any())).thenThrow(new RuntimeException("LLM timeout"));
            when(promptAssembler.buildHistoryBlock(any(), anyBoolean())).thenReturn("");

            ChatResult r =
                    chatService.chat(
                            new ChatCommand("那 Hystrix 呢", null, 5), TID, CONV_ID);

            assertThat(r.stateHint()).isEqualTo(StateHint.LLM_DEGRADED);
            // 关键: history 不写回 (防 LLM 出错消息污染下次 rewrite)
            verify(conversationStore, never()).save(any());
        }

        @Test
        @DisplayName("conversationId 为 null → 完全走 stateless 老路径, store/rewriter 都不调")
        void conversationIdNull_shouldStayStateless() throws Exception {
            when(documentRepository.countByStatus(DocumentStatus.INDEXED)).thenReturn(1L);
            when(retrieveService.retrieve(any()))
                    .thenReturn(
                            new RetrieveService.RetrieveResult(
                                    List.of(
                                            new RetrieveService.Citation(
                                                    19L, 6L, 0,
                                                    "Sentinel 文本", "Sentinel 文本", 0.9f,
                                                    List.of())),
                                    "not_enabled", 0.9f, 0f));
            when(chatClient.chat(any(), any())).thenReturn("answer");

            ChatResult r = chatService.chat(new ChatCommand("Sentinel?", null, 5), TID, null);

            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            verify(conversationStore, never()).findById(any());
            verify(queryContextualizer, never()).contextualize(any(), any());
            verify(conversationStore, never()).save(any());
        }
    }
}
