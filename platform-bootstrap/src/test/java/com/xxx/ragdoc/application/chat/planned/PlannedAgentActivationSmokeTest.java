package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.pipeline.ChatOrchestrator;
import com.xxx.ragdoc.application.chat.pipeline.ChatPipeline;
import com.xxx.ragdoc.application.chat.pipeline.ChatPipelineRegistry;
import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.RouterProperties;
import com.xxx.ragdoc.application.chat.router.RuleBasedTaskRouter;
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
import org.junit.jupiter.api.Test;

/**
 * PR-7f.2c-pre: 端到端 activation-gate 烟测。
 *
 * <p>不启动 Spring 容器, 不调真实 LLM/检索; 仅断言 activation gate 在 default-AUTO 路径上各阶段语义连贯:
 *
 * <ol>
 *   <li>Router 产出 MULTI_HOP / 高置信 RouterDecision
 *   <li>ExecutionStrategyResolver.resolve(...) 在 flag=true 升级到 PLANNED_AGENT
 *   <li>Orchestrator 在 PipelineType.PLANNED_AGENT 上调用 PlannedAgentPipeline
 *   <li>PlannedAgentPipeline.execute 被调用, 任意返回一个 ChatResult
 *   <li>ChatResult.pipelineType = PLANNED_AGENT (出口装饰生效)
 * </ol>
 *
 * <p>反向用例: flag=false 时 Orchestrator 走 ClassicRagPipeline, 不触发 PLANNED_AGENT — zero-diff 保留。
 */
@DisplayName("PR-7f.2c-pre Runtime Activation SmokeTest")
class PlannedAgentActivationSmokeTest {

    private static final TraceId TID = new TraceId("smoke-tid");
    private static final Principal PRINCIPAL = new Principal("tenant-A", "user-1", Set.of(), "tok");

    private ChatPipelineRegistry registry;
    private ChatPipeline classicPipeline;
    private ChatPipeline plannedPipeline;
    private TraceObserver traceObserver;
    private RouterProperties routerProperties;
    private PlannerProperties plannerProperties;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL);
        registry = mock(ChatPipelineRegistry.class);
        classicPipeline = mock(ChatPipeline.class);
        plannedPipeline = mock(ChatPipeline.class);
        when(classicPipeline.type()).thenReturn(PipelineType.CLASSIC_RAG);
        when(plannedPipeline.type()).thenReturn(PipelineType.PLANNED_AGENT);
        when(registry.get(PipelineType.CLASSIC_RAG)).thenReturn(classicPipeline);
        when(registry.get(PipelineType.PLANNED_AGENT)).thenReturn(plannedPipeline);
        traceObserver = mock(TraceObserver.class);
        routerProperties = new RouterProperties();
        routerProperties.setEnabled(true);
        plannerProperties = new PlannerProperties();
        plannerProperties.setEnabled(true);
        plannerProperties.setMinRouterConfidence(0.80);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    /** 用一个总是 MULTI_HOP 高置信的假 Router 复用 RuleBasedTaskRouter 接口。 */
    private RuleBasedTaskRouter multiHopRouter() {
        return new RuleBasedTaskRouter() {
            @Override
            public RouterDecision route(String query) {
                return new RouterDecision(
                        TaskIntent.MULTI_HOP,
                        ExecutionStrategy.CLASSIC_RAG,
                        List.of(), Map.of(), 0.95, "SMOKE_MULTI_HOP");
            }
        };
    }

    @Test
    @DisplayName("flag=true + MULTI_HOP → PLANNED_AGENT pipeline 被调用 + pipelineType 出口附加")
    void flagTrueRoutesToPlannedAgent() {
        plannerProperties.setPlannedPipelineEnabled(true);
        ExecutionStrategyResolver resolver = new ExecutionStrategyResolver(plannerProperties);
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                registry, traceObserver, routerProperties, multiHopRouter(), resolver);

        ChatResult stub = ChatResult.of(StateHint.OK, "agent answer", TID);
        when(plannedPipeline.execute(any(), any())).thenReturn(stub);

        ChatResult r = orchestrator.execute(
                new ChatCommand("q", null, 5), TID, ChatMode.AUTO);

        verify(plannedPipeline, times(1)).execute(any(), any());
        verify(classicPipeline, times(0)).execute(any(), any());
        verify(registry, times(1)).get(PipelineType.PLANNED_AGENT);
        assertThat(r.pipelineType()).isEqualTo(PipelineType.PLANNED_AGENT);
        assertThat(r.answer()).isEqualTo("agent answer");
    }

    @Test
    @DisplayName("flag=false (默认) + MULTI_HOP → zero-diff, ClassicRagPipeline 被调用, 不触发 Planned")
    void flagDefaultFalseZeroDiff() {
        // plannerProperties 默认 plannedPipelineEnabled=false
        ExecutionStrategyResolver resolver = new ExecutionStrategyResolver(plannerProperties);
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                registry, traceObserver, routerProperties, multiHopRouter(), resolver);

        ChatResult stub = ChatResult.of(StateHint.OK, "classic answer", TID);
        when(classicPipeline.execute(any(), any())).thenReturn(stub);

        ChatResult r = orchestrator.execute(
                new ChatCommand("q", null, 5), TID, ChatMode.AUTO);

        verify(classicPipeline, times(1)).execute(any(), any());
        verify(plannedPipeline, times(0)).execute(any(), any());
        assertThat(r.pipelineType()).isEqualTo(PipelineType.CLASSIC_RAG);
    }

    @Test
    @DisplayName("flag=true 但 Router 给低置信 (<0.80) → 不升级, 保留 Classic")
    void flagTrueButLowConfidenceStaysClassic() {
        plannerProperties.setPlannedPipelineEnabled(true);
        ExecutionStrategyResolver resolver = new ExecutionStrategyResolver(plannerProperties);
        RuleBasedTaskRouter lowConfRouter = new RuleBasedTaskRouter() {
            @Override
            public RouterDecision route(String query) {
                return new RouterDecision(
                        TaskIntent.MULTI_HOP,
                        ExecutionStrategy.CLASSIC_RAG,
                        List.of(), Map.of(), 0.5, "SMOKE_LOW_CONF");
            }
        };
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                registry, traceObserver, routerProperties, lowConfRouter, resolver);

        when(classicPipeline.execute(any(), any()))
                .thenReturn(ChatResult.of(StateHint.OK, "x", TID));

        ChatResult r = orchestrator.execute(
                new ChatCommand("q", null, 5), TID, ChatMode.AUTO);

        verify(plannedPipeline, times(0)).execute(any(), any());
        verify(classicPipeline, times(1)).execute(any(), any());
        assertThat(r.pipelineType()).isEqualTo(PipelineType.CLASSIC_RAG);
    }

    @Test
    @DisplayName("flag=true 但 Router intent=FACT (非 MULTI_HOP) → 不升级, 保留 Classic")
    void flagTrueButNonMultiHopStaysClassic() {
        plannerProperties.setPlannedPipelineEnabled(true);
        ExecutionStrategyResolver resolver = new ExecutionStrategyResolver(plannerProperties);
        RuleBasedTaskRouter factRouter = new RuleBasedTaskRouter() {
            @Override
            public RouterDecision route(String query) {
                return new RouterDecision(
                        TaskIntent.FACT,
                        ExecutionStrategy.CLASSIC_RAG,
                        List.of(), Map.of(), 0.95, "SMOKE_FACT");
            }
        };
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                registry, traceObserver, routerProperties, factRouter, resolver);

        when(classicPipeline.execute(any(), any()))
                .thenReturn(ChatResult.of(StateHint.OK, "x", TID));

        orchestrator.execute(new ChatCommand("q", null, 5), TID, ChatMode.AUTO);

        verify(plannedPipeline, times(0)).execute(any(), any());
        verify(classicPipeline, times(1)).execute(any(), any());
    }
}
