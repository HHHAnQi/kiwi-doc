package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
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
    }

    @Nested
    @DisplayName("EMPTY_KB: 0 个 READY 文档")
    class EmptyKb {
        @Test
        @DisplayName("应返回 state_hint=EMPTY_KB + 友好文案 + 空 citations")
        void shouldReturnEmptyKb() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

            ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.EMPTY_KB);
            assertThat(r.answer()).isEqualTo("EMPTY_MSG");
            assertThat(r.citations()).isEmpty();
        }

        @Test
        @DisplayName("必须落 chat_traces 记录(state_hint=EMPTY_KB)")
        void shouldWriteChatTrace() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

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
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(3L);
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
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(1L);
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
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(1L);
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
        when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

        ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

        assertThat(r.traceId()).isEqualTo(TID);
    }
}
