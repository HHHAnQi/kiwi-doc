package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * PR-3.4: {@link ComparisonWorkflowPipeline} A/B 子查询 + 合并 + 单终态守门测试。
 *
 * <p>不启动 Spring 容器, 仅 mock {@link ChatService} 验证工作流可视化行为。
 */
@DisplayName("ComparisonWorkflowPipeline - PR-3.4")
class ComparisonWorkflowPipelineTest {

    private static final TraceId TID = new TraceId("workflow-trace");
    private static final Principal PRINCIPAL =
            new Principal("tenant-A", "user-1", Set.of(), "tok");

    private ChatService chatService;
    private ComparisonWorkflowPipeline pipeline;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL);
        chatService = mock(ChatService.class);
        // PR-6c: PR-3 旧路径测试 — Flag=false, agentExecutor=mock
        com.xxx.ragdoc.application.chat.comparison.ComparisonAgentExecutor agentExecutor =
                mock(com.xxx.ragdoc.application.chat.comparison.ComparisonAgentExecutor.class);
        com.xxx.ragdoc.application.chat.comparison.ComparisonExecutorProperties props =
                new com.xxx.ragdoc.application.chat.comparison.ComparisonExecutorProperties();
        props.setComparisonExecutorEnabled(false);
        pipeline = new ComparisonWorkflowPipeline(chatService, agentExecutor, props);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    private ChatExecutionContext ctxWithEntities(List<String> entities) {
        RouterDecision d =
                new RouterDecision(
                        TaskIntent.COMPARISON,
                        ExecutionStrategy.FIXED_WORKFLOW,
                        entities,
                        Map.of(),
                        0.92,
                        "COMPARISON_TWO_OBJECTS");
        return new ChatExecutionContext(
                "req-1",
                PRINCIPAL,
                ChatMode.AUTO,
                PipelineType.FIXED_WORKFLOW,
                TID,
                ExecutionPolicy.defaults(),
                d);
    }

    private ChatResult okWith(Long... chunkIds) {
        List<ChatResult.Citation> cits =
                java.util.Arrays.stream(chunkIds)
                        .map(
                                id ->
                                        new ChatResult.Citation(
                                                id,
                                                100L,
                                                0,
                                                "片段" + id,
                                                "上下文" + id,
                                                java.util.List.<String>of(),
                                                null))
                        .toList();
        return new ChatResult("答案正文", cits, StateHint.OK, TID, null, null);
    }

    private ChatResult noRecall() {
        return ChatResult.of(StateHint.NO_RECALL, "(无召回)", TID);
    }

    @Test
    @DisplayName("type() = FIXED_WORKFLOW")
    void typeIsFixedWorkflow() {
        assertThat(pipeline.type()).isEqualTo(PipelineType.FIXED_WORKFLOW);
    }

    @Nested
    @DisplayName("extractComparisonPair: 从 entities 或 filters 抽 A/B")
    class PairExtraction {

        @Test
        @DisplayName("entities 有 2 个 → 抽出 A/B")
        void entitiesBased() {
            ChatCommand cmd = new ChatCommand("比较 Sentinel 和 Hystrix", null, 5);
            ComparisonWorkflowPipeline.Pair p =
                    ComparisonWorkflowPipeline.extractComparisonPair(cmd, ctxWithEntities(List.of("Sentinel", "Hystrix")));
            assertThat(p.a()).isEqualTo("Sentinel");
            assertThat(p.b()).isEqualTo("Hystrix");
        }

        @Test
        @DisplayName("entities 不足 → fallback 到 filters['versions']")
        void fallbacksToFilters() {
            ChatExecutionContext ctx =
                    new ChatExecutionContext(
                            "req",
                            PRINCIPAL,
                            ChatMode.AUTO,
                            PipelineType.FIXED_WORKFLOW,
                            TID,
                            ExecutionPolicy.defaults(),
                            new RouterDecision(
                                    TaskIntent.COMPARISON,
                                    ExecutionStrategy.FIXED_WORKFLOW,
                                    List.of(),
                                    Map.of("versions", List.of("v1.0", "v2.0")),
                                    0.92,
                                    "COMPARISON_TWO_OBJECTS"));
            ComparisonWorkflowPipeline.Pair p =
                    ComparisonWorkflowPipeline.extractComparisonPair(new ChatCommand("比较 v1.0 和 v2.0", null, 5), ctx);
            assertThat(p.a()).isEqualTo("v1.0");
            assertThat(p.b()).isEqualTo("v2.0");
        }

        @Test
        @DisplayName("无 A/B → null (调用方回退 Classic, 不假装 workflow 成功)")
        void noPairReturnsNull() {
            ChatCommand cmd = new ChatCommand("比较一下", null, 5);
            ComparisonWorkflowPipeline.Pair p =
                    ComparisonWorkflowPipeline.extractComparisonPair(
                            cmd,
                            new ChatExecutionContext(
                                    "req",
                                    PRINCIPAL,
                                    ChatMode.AUTO,
                                    PipelineType.FIXED_WORKFLOW,
                                    TID,
                                    ExecutionPolicy.defaults()));
            assertThat(p).isNull();
        }
    }

    @Nested
    @DisplayName("execute: A/B 子查询 + Evidence 合并 + 答案拼接")
    class Execute {

        @Test
        @DisplayName("A、B 都成功有 citations → 合并答案 + 合并 citations, stateHint=OK")
        void bothSidesHaveEvidence() {
            ChatCommand cmd = new ChatCommand("比较 v1.0 和 v2.0 权限差异", null, 5);
            // A 答案 + 两个 citation; B 答案 + 一个 citation (与 A 共享 chunkId=10L)
            ChatResult aResult = okWith(10L, 11L);
            ChatResult bResult = okWith(10L, 20L);
            when(chatService.chat(any(), eq(TID), any())).thenReturn(aResult, bResult);

            ChatResult r = pipeline.execute(cmd, ctxWithEntities(List.of("v1.0", "v2.0")));

            // 两次 ChatService 调用 (A + B)
            verify(chatService, times(2)).chat(any(), eq(TID), any());
            // 合并去重: A {10, 11} + B {10, 20} → {10, 11, 20}
            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.citations())
                    .extracting(ChatResult.Citation::chunkId)
                    .containsExactly(10L, 11L, 20L);
            // 答案包含 A/B 双方
            assertThat(r.answer()).contains("v1.0").contains("v2.0").contains("答案正文");
        }

        @Test
        @DisplayName("A 有 citations 但 B 无 Evidence (NO_RECALL) → 不拼凑, 单终态 NO_RECALL")
        void oneSideLacksEvidence() {
            ChatCommand cmd = new ChatCommand("比较 A 和 B", null, 5);
            when(chatService.chat(any(), eq(TID), any())).thenReturn(okWith(1L), noRecall());

            ChatResult r = pipeline.execute(cmd, ctxWithEntities(List.of("A", "B")));

            assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
            assertThat(r.answer()).contains("缺乏可引用");
            assertThat(r.citations()).isEmpty();
        }

        @Test
        @DisplayName("entities 不足 → 回退 Classic, 不调 ChatService.chat 二次")
        void fallbackToClassicWhenNoAb() {
            ChatCommand cmd = new ChatCommand("比较一下", null, 5);
            ChatResult fallback = okWith(99L);
            when(chatService.chat(any(), eq(TID), any())).thenReturn(fallback);

            ChatResult r = pipeline.execute(cmd, ctxWithEntities(List.of())); // 无 entities

            // 只调一次 (回退 single Classic chat, 不二次)
            verify(chatService, times(1)).chat(any(), eq(TID), any());
            assertThat(r).isSameAs(fallback);
        }
    }

    @Nested
    @DisplayName("SSE: 第一版回退 Classic stream, 保证单终态")
    class StreamFallback {

        @Test
        void streamDelegatesToClassic() {
            ChatCommand cmd = new ChatCommand("比较 v1.0 和 v2.0 权限差异", null, 5);
            ChatStreamEvent head = new ChatStreamEvent.DoneEvent(TID.value(), StateHint.OK.name());
            when(chatService.chatStream(any(), eq(TID))).thenReturn(Flux.just(head));

            List<ChatStreamEvent> events =
                    pipeline.stream(cmd, ctxWithEntities(List.of("v1.0", "v2.0")))
                            .collectList()
                            .block();

            assertThat(events).hasSize(1);
            // 流式不调 chat(同步路径), 单终态契约由 Classic 的 chatStream 保
            verify(chatService, times(0)).chat(any(), eq(TID), any());
            verify(chatService, times(1)).chatStream(any(), eq(TID));
        }
    }
}
