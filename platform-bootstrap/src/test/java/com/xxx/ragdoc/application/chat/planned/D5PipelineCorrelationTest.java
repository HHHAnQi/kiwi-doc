package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.pipeline.ChatExecutionContext;
import com.xxx.ragdoc.application.chat.pipeline.ClassicRagPipeline;
import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

/** P2-D5(B/C) T6/T7: correlation contract — runId 只在真实存在时暴露; sync 头与 SSE 终态事件同语义。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2-D5 T6/T7 — correlation contract(sync/SSE一致, 无fake runId)")
class D5PipelineCorrelationTest {

    private static final TraceId TID = new TraceId("d5-trace-1");

    @Mock private PlannedAgentExecutionCoordinator coordinator;
    @Mock private DefaultEvidenceGroundedAnswerComposer composer;
    @Mock private PlannedAgentRunFinalizer runFinalizer;
    @Mock private com.xxx.ragdoc.application.chat.RetrieveService retrieveService;
    @Mock private ClassicRagPipeline classicPipeline;
    @Mock private com.xxx.ragdoc.application.chat.agent.AgentBudgetProperties budgetProps;

    private PlannerProperties plannerProps;
    private PlannedAgentPipeline pipeline;

    @BeforeEach
    void setup() {
        plannerProps = new PlannerProperties();
        plannerProps.setEnabled(true);
        when(retrieveService.retrieve(any()))
                .thenReturn(
                        new com.xxx.ragdoc.application.chat.RetrieveService.RetrieveResult(
                                List.of(), null, 0f, 0f, null));
        when(runFinalizer.finalize(
                        anyString(), anyLong(), anySet(), any(), anyString(), any(), any(), any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                "run-ok", 7L, AgentRunStatus.ANSWERED));
        pipeline =
                new PlannedAgentPipeline(
                        coordinator,
                        composer,
                        runFinalizer,
                        plannerProps,
                        budgetProps,
                        retrieveService,
                        classicPipeline);
    }

    @AfterEach
    void clearMdc() {
        org.slf4j.MDC.clear();
    }

    private ChatExecutionContext ctx() {
        RouterDecision decision =
                new RouterDecision(
                        com.xxx.ragdoc.application.chat.router.TaskIntent.MULTI_HOP,
                        com.xxx.ragdoc.application.chat.router.ExecutionStrategy.PLANNED_AGENT,
                        List.of("Seata"),
                        java.util.Map.of(),
                        0.95,
                        "TEST");
        return new ChatExecutionContext(
                "req-d5-1",
                new com.xxx.ragdoc.domain.auth.Principal("tA", "u1", java.util.Set.of(), null),
                com.xxx.ragdoc.domain.shared.ChatMode.AGENTIC,
                PipelineType.PLANNED_AGENT,
                TID,
                null,
                decision);
    }

    private PlannedAgentExecutionCoordinator.PreparedGroundedAnswer prepared(String runId) {
        return new PlannedAgentExecutionCoordinator.PreparedGroundedAnswer(
                runId,
                "req-d5-1",
                "q",
                List.of(),
                List.of(),
                List.of(),
                com.xxx.ragdoc.application.chat.agent.AgentUsage.zero(),
                com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation.zero(),
                0,
                java.time.Instant.now(),
                com.xxx.ragdoc.application.chat.agent.CancellationTokenSource.CancellationToken
                        .never(),
                6L);
    }

    // ── T6: pre-run planner 全灭 → Classic fallback ──

    @Test
    @DisplayName("T6: INITIAL_PLANNER_FAILED→Classic兜底: 不暴露runId(无fake), 委托Classic, traceId在结果")
    void t6_preRunFallbackNoFakeRunId() {
        when(coordinator.prepare(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        PlannedAgentExecutionCoordinator.PrepareResult.structuralFailure(
                                "INITIAL_PLANNER_FAILED"));
        when(classicPipeline.execute(any(), any()))
                .thenReturn(
                        new ChatResult(
                                "classic answer",
                                List.of(),
                                StateHint.OK,
                                TID,
                                null,
                                null,
                                PipelineType.CLASSIC_RAG,
                                null));

        ChatResult r = pipeline.execute(new ChatCommand("q", null, 5), ctx());

        verify(classicPipeline).execute(any(), any()); // 兜底真实发生
        assertThat(r.answer()).isEqualTo("classic answer");
        assertThat(r.traceId()).isEqualTo(TID); // traceId 可关联
        // 不造 fake: run 未创建 → MDC 无 runId → 响应无 X-Agent-Run-Id
        assertThat(org.slf4j.MDC.get("rag.agentRunId")).isNull();
    }

    // ── T7: sync 与 SSE 同语义 ──

    @Test
    @DisplayName("T7a: sync 成功 → MDC 携带 runId+terminalStatus=ANSWERED(供 X-Agent-* 头)")
    void t7a_syncCorrelation() throws Exception {
        when(coordinator.prepare(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PlannedAgentExecutionCoordinator.PrepareResult.ok(prepared("run-ok")));
        when(composer.compose(any()))
                .thenReturn(new EvidenceGroundedAnswerComposer.GroundedAnswer("ans", List.of()));

        ChatResult r = pipeline.execute(new ChatCommand("q", null, 5), ctx());
        assertThat(r.answer()).isEqualTo("ans");
        assertThat(org.slf4j.MDC.get("rag.agentRunId")).isEqualTo("run-ok");
        assertThat(org.slf4j.MDC.get("rag.agentTerminalStatus")).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("T7b: SSE 终态 DoneEvent 携带 runId/terminalStatus(与sync同语义), ErrorEvent携带真实runId")
    void t7b_sseParity() {
        when(coordinator.prepare(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PlannedAgentExecutionCoordinator.PrepareResult.ok(prepared("run-ok")));
        when(composer.stream(any())).thenReturn(Flux.just(new ChatStreamEvent.DeltaEvent("a")));

        List<ChatStreamEvent> events =
                pipeline.stream(new ChatCommand("q", null, 5), ctx())
                        .collectList()
                        .block(java.time.Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        ChatStreamEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(ChatStreamEvent.DoneEvent.class);
        ChatStreamEvent.DoneEvent done = (ChatStreamEvent.DoneEvent) last;
        assertThat(done.traceId()).isEqualTo(TID.value());
        assertThat(done.runId()).isEqualTo("run-ok");
        assertThat(done.terminalStatus()).isEqualTo("ANSWERED");

        // 失败路径: 已创建run的失败 → ErrorEvent携带真实runId
        when(coordinator.prepare(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        PlannedAgentExecutionCoordinator.PrepareResult.prematureFailure(
                                "run-x", AgentRunStatus.REFUSED_CONFLICT, "CONFLICT"));
        List<ChatStreamEvent> errEvents =
                pipeline.stream(new ChatCommand("q", null, 5), ctx())
                        .collectList()
                        .block(java.time.Duration.ofSeconds(10));
        ChatStreamEvent errLast = errEvents.get(errEvents.size() - 1);
        assertThat(errLast).isInstanceOf(ChatStreamEvent.ErrorEvent.class);
        assertThat(((ChatStreamEvent.ErrorEvent) errLast).runId()).isEqualTo("run-x");
    }

    @Test
    @DisplayName("T7c: sync 已创建run的失败 → MDC 暴露真实runId+terminalStatus(供响应头)")
    void t7c_syncFailureExposesRunId() {
        when(coordinator.prepare(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        PlannedAgentExecutionCoordinator.PrepareResult.prematureFailure(
                                "run-y",
                                AgentRunStatus.REFUSED_NO_EVIDENCE,
                                "INSUFFICIENT_AFTER_REPLAN_NO_EVIDENCE"));
        ChatResult r = pipeline.execute(new ChatCommand("q", null, 5), ctx());
        assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
        assertThat(org.slf4j.MDC.get("rag.agentRunId")).isEqualTo("run-y");
        assertThat(org.slf4j.MDC.get("rag.agentTerminalStatus")).isEqualTo("REFUSED_NO_EVIDENCE");
    }
}
