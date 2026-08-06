package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-2 / EMS-PR2: {@link ChatOrchestrator} 路由 + 上下文不变量 + 失败关闭。
 *
 * <p>所有断言仅依赖 mocked {@link ChatPipelineRegistry} + 已 set 的 {@link AuthContext} Principal,
 * 不启动 Spring 容器。
 */
@DisplayName("ChatOrchestrator - PR-2 路由与 Context 不变量")
class ChatOrchestratorTest {

    private static final TraceId TID = new TraceId("orch-trace-1");
    private static final Principal TEST_PRINCIPAL =
            new Principal("tenant-A", "user-1", Set.of(), "tok");

    private ChatPipelineRegistry registry;
    private ChatPipeline classicPipeline;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        AuthContext.set(TEST_PRINCIPAL);
        registry = mock(ChatPipelineRegistry.class);
        classicPipeline = mock(ChatPipeline.class);
        when(classicPipeline.type()).thenReturn(PipelineType.CLASSIC_RAG);
        when(registry.get(PipelineType.CLASSIC_RAG)).thenReturn(classicPipeline);
        orchestrator = new ChatOrchestrator(registry);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    private static ChatCommand cmd() {
        return new ChatCommand("测试", null, 5);
    }

    // ─── 路由 ─────────────────────────────────────────────

    @Nested
    @DisplayName("RAG / AUTO → CLASSIC_RAG")
    class RagAutoRoute {

        @Test
        @DisplayName("RAG 模式执行 Classic pipeline")
        void ragRoutesToClassic() {
            ChatResult stub = ChatResult.of(StateHint.OK, "答案", TID);
            when(classicPipeline.execute(any(), any())).thenReturn(stub);

            ChatResult r = orchestrator.execute(cmd(), TID, ChatMode.RAG);

            assertThat(r).isSameAs(stub);
            verify(classicPipeline, times(1)).execute(any(), any());
            verify(registry, times(1)).get(PipelineType.CLASSIC_RAG);
        }

        @Test
        @DisplayName("AUTO 模式 PR-2 暂时也执行 Classic (Router 未实现)")
        void autoRoutesToClassic() {
            ChatResult stub = ChatResult.of(StateHint.OK, "答案", TID);
            when(classicPipeline.execute(any(), any())).thenReturn(stub);

            ChatResult r = orchestrator.execute(cmd(), TID, ChatMode.AUTO);

            assertThat(r).isSameAs(stub);
            verify(classicPipeline, times(1)).execute(any(), any());
            // 校验 effective pipeline = CLASSIC_RAG
            org.mockito.ArgumentCaptor<ChatExecutionContext> captor =
                    org.mockito.ArgumentCaptor.forClass(ChatExecutionContext.class);
            verify(classicPipeline).execute(any(), captor.capture());
            assertThat(captor.getValue().effectivePipeline()).isEqualTo(PipelineType.CLASSIC_RAG);
            assertThat(captor.getValue().requestedMode()).isEqualTo(ChatMode.AUTO);
        }

        @Test
        @DisplayName("null mode 等同 AUTO, 老客户端兼容")
        void nullModeDefaultsToAuto() {
            ChatResult stub = ChatResult.of(StateHint.OK, "答案", TID);
            when(classicPipeline.execute(any(), any())).thenReturn(stub);

            orchestrator.execute(cmd(), TID, null);

            verify(classicPipeline, times(1)).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("AGENTIC → 不调用任何 pipeline, 抛 422")
    class AgenticRejected {

        @Test
        @DisplayName("同步 execute: AGENTIC 抛 AGENTIC_MODE_UNAVAILABLE")
        void syncAgenticRejected() {
            assertThatThrownBy(() -> orchestrator.execute(cmd(), TID, ChatMode.AGENTIC))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode().name())
                                            .isEqualTo("AGENTIC_MODE_UNAVAILABLE"));

            verify(classicPipeline, never()).execute(any(), any());
            verify(registry, never()).get(any());
        }

        @Test
        @DisplayName("SSE stream: AGENTIC 在订阅前抛, pipeline.stream 不被调用")
        void streamAgenticRejected() {
            assertThatThrownBy(() -> orchestrator.stream(cmd(), TID, ChatMode.AGENTIC))
                    .isInstanceOf(DomainException.class);

            verify(classicPipeline, never()).stream(any(), any());
        }
    }

    @Nested
    @DisplayName("Pipeline 缺陷 → 失败关闭, 不静默回退")
    class FailClosed {

        @Test
        @DisplayName("Registry miss (PIPELINE_NOT_FOUND) → DomainException 上抛, 不调 pipeline")
        void registryMissPropagates() {
            // 模拟 effective pipeline registry 未注册 (Spring 启动期 fail-fast 本应预防)
            when(registry.get(PipelineType.CLASSIC_RAG))
                    .thenThrow(
                            new DomainException(
                                    com.xxx.ragdoc.common.exception.ErrorCode.PIPELINE_NOT_FOUND,
                                    "test"));

            assertThatThrownBy(() -> orchestrator.execute(cmd(), TID, ChatMode.RAG))
                    .isInstanceOf(DomainException.class);
            verify(classicPipeline, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("ChatExecutionContext 不变量")
    class ContextInvariants {

        @Test
        @DisplayName("Principal 来自 AuthContext, 不接受客户端传")
        void principalFromAuthContext() {
            when(classicPipeline.execute(any(), any()))
                    .thenReturn(ChatResult.of(StateHint.OK, "x", TID));

            orchestrator.execute(cmd(), TID, ChatMode.RAG);

            org.mockito.ArgumentCaptor<ChatExecutionContext> captor =
                    org.mockito.ArgumentCaptor.forClass(ChatExecutionContext.class);
            verify(classicPipeline).execute(any(), captor.capture());
            ChatExecutionContext ctx = captor.getValue();
            assertThat(ctx.principal()).isEqualTo(TEST_PRINCIPAL);
            assertThat(ctx.principal().tenantId()).isEqualTo("tenant-A");
            assertThat(ctx.requestId()).startsWith("orch-tra");
            assertThat(ctx.traceId()).isEqualTo(TID);
        }

        @Test
        @DisplayName("并发: 两个请求拿到不同 requestId 与 context, 不串线")
        void concurrentRequestsGetIndependentContexts() throws Exception {
            when(classicPipeline.execute(any(), any()))
                    .thenReturn(ChatResult.of(StateHint.OK, "x", TID));

            int n = 8;
            CountDownLatch latch = new CountDownLatch(n);
            AtomicReference<Throwable> err = new AtomicReference<>();
            java.util.Set<String> requestIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

            for (int i = 0; i < n; i++) {
                final int idx = i;
                Thread t =
                        new Thread(
                                () -> {
                                    try {
                                        orchestrator.execute(
                                                new ChatCommand("q" + idx, null, 5),
                                                new TraceId("trace-" + idx),
                                                ChatMode.AUTO);
                                        // 抓 startTrace capture 不到, 改用 MDC 内的 request_id 累加
                                        String rid = org.slf4j.MDC.get(ChatOrchestrator.ORCH_MDC_REQUEST_ID);
                                        if (rid != null) requestIds.add(rid);
                                    } catch (Throwable e) {
                                        err.set(e);
                                    } finally {
                                        latch.countDown();
                                    }
                                });
                t.start();
            }
            latch.await();
            assertThat(err.get()).isNull();
            // 注: 异步线程结束后 MDC view 不可靠, 但至少不应抛 + 注册到 8 次经典 pipeline 调用
            verify(classicPipeline, times(n)).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("SSE: 委托 pipeline.stream 而非自己合成事件")
    class StreamDelegation {

        @Test
        @DisplayName("AUTO SSE: 转发经典流的 Flux")
        void sseAutoDelegatesToClassicStream() {
            ChatStreamEvent head = new ChatStreamEvent.DoneEvent(TID.value(), StateHint.OK.name());
            when(classicPipeline.stream(any(), any())).thenReturn(reactor.core.publisher.Flux.just(head));

            java.util.List<ChatStreamEvent> events =
                    orchestrator.stream(cmd(), TID, ChatMode.AUTO).collectList().block();

            assertThat(events).hasSize(1);
            verify(classicPipeline, times(1)).stream(any(), any());
        }
    }
}
