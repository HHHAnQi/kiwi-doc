package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent.CitationsEvent;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent.DoneEvent;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * PR-0 门禁: SSE 流式问答的<b>单终态不变量</b>与<b>客户端取消传播</b>。
 *
 * <p>不变量 (跨所有路径):
 *
 * <ol>
 *   <li>每次 chatStream 产出的流, 终止事件 (DoneEvent) 恰好出现一次
 *   <li>成功流: CitationsEvent → N×DeltaEvent → 1×DoneEvent(OK), 不出现 ErrorEvent / 第二个 DoneEvent
 *   <li>LLM 失败流: CitationsEvent → (可选 N×Delta) → 1×DoneEvent(LLM_DEGRADED), 不出现 OK DoneEvent
 *   <li>EMPTY_KB / NO_RECALL: 恰好 1×DoneEvent, 无 CitationsEvent
 *   <li>客户端在 LLM 流中途 cancel (StepVerifier cancelAfter) → 上游 LLM 流被取消 (chatStream 不再产出后续 token),
 *       不产生第二个终态
 * </ol>
 *
 * <p>这一层测试曾暴露 EMS-PR0 关键缺陷: onErrorResume 转 DoneEvent(DEGRADED) 后 concatWith 的 defer 仍会再发一个
 * DoneEvent(OK) → 双终态。修复后必须仍能通过本测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceStreamTerminalStateTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private ChatMessages chatMessages;
    @Mock private RetrieveService retrieveService;
    @Mock private ChatClient chatClient;
    @Mock private TraceObserver traceObserver;
    @Mock private MetricsPort metrics;

    @Mock
    private com.xxx.ragdoc.application.chat.conversation.port.ConversationStore conversationStore;

    @Mock
    private com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort
            queryContextualizer;

    @Mock
    private com.xxx.ragdoc.application.chat.conversation.port.PromptAssemblerPort promptAssembler;

    @InjectMocks private ChatService chatService;

    private static final TraceId TID = new TraceId("trace-pr0-sse");

    @BeforeEach
    void setup() {
        when(chatMessages.getEmptyKbMessage()).thenReturn("EMPTY_MSG");
        when(chatMessages.getNoRecallMessage()).thenReturn("NORECALL_MSG");
        when(chatMessages.getLlmDegradedMessage()).thenReturn("LLM_FAIL:");
        lenient().when(traceObserver.startTrace(any(), any(), any())).thenReturn(TID.value());
        chatService.setConversationDeps(conversationStore, queryContextualizer, promptAssembler);
    }

    /** 共用: 构造一条命中检索结果。 */
    private void mockNonEmptyRetrieve() {
        when(documentRepository.countByStatus(
                        com.xxx.ragdoc.domain.document.DocumentStatus.INDEXED))
                .thenReturn(1L);
        when(retrieveService.retrieve(any()))
                .thenReturn(
                        new RetrieveService.RetrieveResult(
                                List.of(
                                        new RetrieveService.Citation(
                                                19L, 6L, 0, "片段A", "片段A", 0.9f, List.of())),
                                "not_enabled",
                                0.9f,
                                0f));
    }

    private long countDone(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e instanceof DoneEvent).count();
    }

    @Nested
    @DisplayName("EMPTY_KB / NO_RECALL: 恰好一个终态")
    class DegradedShortCircuit {

        @Test
        @DisplayName("EMPTY_KB 流只发 1 个 DoneEvent(EMPTY_KB)")
        void emptyKbSingleTerminal() {
            when(documentRepository.countByStatus(
                            com.xxx.ragdoc.domain.document.DocumentStatus.INDEXED))
                    .thenReturn(0L);

            List<ChatStreamEvent> events =
                    chatService
                            .chatStream(new ChatCommand("你好", null, 5), TID)
                            .collectList()
                            .block();

            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(DoneEvent.class);
            assertThat(((DoneEvent) events.get(0)).stateHint())
                    .isEqualTo(StateHint.EMPTY_KB.name());
        }

        @Test
        @DisplayName("NO_RECALL 流只发 1 个 DoneEvent(NO_RECALL)")
        void noRecallSingleTerminal() {
            when(documentRepository.countByStatus(
                            com.xxx.ragdoc.domain.document.DocumentStatus.INDEXED))
                    .thenReturn(3L);
            when(retrieveService.retrieve(any()))
                    .thenReturn(RetrieveService.RetrieveResult.empty());

            List<ChatStreamEvent> events =
                    chatService
                            .chatStream(new ChatCommand("你好", null, 5), TID)
                            .collectList()
                            .block();

            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(DoneEvent.class);
            assertThat(((DoneEvent) events.get(0)).stateHint())
                    .isEqualTo(StateHint.NO_RECALL.name());
        }
    }

    @Nested
    @DisplayName("OK 路径: 恰好 1×DoneEvent(OK)")
    class OkStream {

        @Test
        @DisplayName("成功流 = Citations + N×Delta + 1×Done(OK), 无 ErrorEvent")
        void successSingleTerminal() {
            mockNonEmptyRetrieve();
            when(chatClient.chatStream(any(), any())).thenReturn(Flux.just("你", "好", "世", "界"));

            List<ChatStreamEvent> events =
                    chatService
                            .chatStream(new ChatCommand("你好", null, 5), TID)
                            .collectList()
                            .block();

            assertThat(events).isNotEmpty();
            assertThat(countDone(events)).isEqualTo(1);
            // 头部是 CitationsEvent
            assertThat(events.get(0)).isInstanceOf(CitationsEvent.class);
            // 终态是 OK
            DoneEvent done =
                    events.stream()
                            .filter(e -> e instanceof DoneEvent)
                            .map(e -> (DoneEvent) e)
                            .findFirst()
                            .orElseThrow();
            assertThat(done.stateHint()).isEqualTo(StateHint.OK.name());
            // 不出现 ErrorEvent
            assertThat(events.stream().noneMatch(e -> e instanceof ChatStreamEvent.ErrorEvent))
                    .isTrue();
            // Delta 数 = LLM 产出的 token 数
            long deltas =
                    events.stream().filter(e -> e instanceof ChatStreamEvent.DeltaEvent).count();
            assertThat(deltas).isEqualTo(4);
            // 终态 OK 时落 trace (PR-1: 走双参 save(ChatTrace, EvidenceSnapshot))
            verify(chatTracesRepository, times(1))
                    .save(
                            any(),
                            any(com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot.class));
        }
    }

    @Nested
    @DisplayName("LLM_DEGRADED 路径: 恰好 1×DoneEvent(LLM_DEGRADED), 不重叠 OK 终态")
    class LlmFailedStream {

        @Test
        @DisplayName("LLM 抛错 → 流终止于唯一的 DoneEvent(LLM_DEGRADED), 不发 Done(OK)")
        void llmErrorSingleTerminal() {
            mockNonEmptyRetrieve();
            when(chatClient.chatStream(any(), any()))
                    .thenReturn(Flux.error(new RuntimeException("DashScope timeout")));

            List<ChatStreamEvent> events =
                    chatService
                            .chatStream(new ChatCommand("你好", null, 5), TID)
                            .collectList()
                            .block();

            assertThat(events).isNotEmpty();
            // 关键不变量: 只有一个终态, 且为 LLM_DEGRADED (不能 DEGRADED + OK 两个 DoneEvent)
            assertThat(countDone(events)).isEqualTo(1);
            DoneEvent done =
                    events.stream()
                            .filter(e -> e instanceof DoneEvent)
                            .map(e -> (DoneEvent) e)
                            .findFirst()
                            .orElseThrow();
            assertThat(done.stateHint()).isEqualTo(StateHint.LLM_DEGRADED.name());
            // 不出现 ErrorEvent (controller 兜底分支才用 Error; service 内部都转 Done)
            assertThat(events.stream().noneMatch(e -> e instanceof ChatStreamEvent.ErrorEvent))
                    .isTrue();
            // DEGRADED 路径也应落 trace (PR-1: 走双参 save(ChatTrace, EvidenceSnapshot))
            verify(chatTracesRepository, times(1))
                    .save(
                            any(),
                            any(com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot.class));
        }

        @Test
        @DisplayName("LLM 中途失败 (先吐部分 token 再 error) → 仍只一个 Done(LLM_DEGRADED)")
        void llmErrorAfterSomeTokensSingleTerminal() {
            mockNonEmptyRetrieve();
            when(chatClient.chatStream(any(), any()))
                    .thenReturn(
                            Flux.<String>just("部", "分")
                                    .concatWith(Flux.error(new RuntimeException("mid timeout"))));

            List<ChatStreamEvent> events =
                    chatService
                            .chatStream(new ChatCommand("你好", null, 5), TID)
                            .collectList()
                            .block();

            assertThat(countDone(events)).isEqualTo(1);
            DoneEvent done =
                    events.stream()
                            .filter(e -> e instanceof DoneEvent)
                            .map(e -> (DoneEvent) e)
                            .findFirst()
                            .orElseThrow();
            assertThat(done.stateHint()).isEqualTo(StateHint.LLM_DEGRADED.name());
        }
    }

    @Nested
    @DisplayName("客户端断开 → 上游 LLM 流被取消")
    class ClientCancellation {

        @Test
        @DisplayName("cancel 后上游不再继续产 token (无第二个终态, 无赠送的 OK)")
        void cancelPropagatesUpstream() {
            mockNonEmptyRetrieve();
            // 一个永不自己 complete 的流, 模拟 "仍在生成"
            java.util.concurrent.atomic.AtomicInteger emitted =
                    new java.util.concurrent.atomic.AtomicInteger();
            Flux<String> infinite =
                    Flux.<String>generate(
                                    sink -> {
                                        emitted.incrementAndGet();
                                        sink.next("x");
                                    })
                            // 缓冲小一点避免内存爆炸; cancel 前会被 Take 测试驱动
                            .limitRate(4);

            when(chatClient.chatStream(any(), any())).thenReturn(infinite);

            // 取前几个事件后 cancel (模拟客户端断开)
            StepVerifier.create(chatService.chatStream(new ChatCommand("你好", null, 5), TID))
                    .expectNextMatches(e -> e instanceof CitationsEvent)
                    .thenCancel()
                    .verify();

            // 取消后上游 generate 不应被无限调用 (limitRate 4 + cancel后会过几个, 但绝不会无界)
            // 上界放宽到 64 防止 flaky
            assertThat(emitted.get()).isLessThan(64);
            // 客户端取消 → 不会落 trace (没有进入 concatWith 完成/onErrorResume 失败两条正常 persist 路径)
            // 客户端取消 → 不会落 trace (没有进入完成/失败两条 persist 路径)
            verify(chatTracesRepository, never()).save(any());
            verify(chatTracesRepository, never())
                    .save(
                            any(),
                            any(com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot.class));
        }
    }
}
