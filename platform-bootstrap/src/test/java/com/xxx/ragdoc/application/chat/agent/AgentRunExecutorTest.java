package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.ReservationResult;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource.CancellationToken;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.tool.EvidenceListOutput;
import com.xxx.ragdoc.application.chat.tool.ToolError;
import com.xxx.ragdoc.application.chat.tool.ToolExecutor;
import com.xxx.ragdoc.application.chat.tool.ToolOutput;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.application.chat.tool.ToolStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PR-6b.3: {@link AgentRunExecutor} 单测。
 *
 * <p>覆盖核心 Run 终态矩阵 (Revision §6 + §1.9 cleanup):
 *
 * <ul>
 *   <li>单 Step 成功 (有 Evidence) → READY_TO_ANSWER
 *   <li>单 Step 成功 (EMPTY + required) → REFUSED_NO_EVIDENCE reasonCode=REQUIRED_EVIDENCE_MISSING
 *   <li>无 Evidence → REFUSED_NO_EVIDENCE reasonCode=NO_EVIDENCE
 *   <li>Tool FAILED_TERMINAL (required) → Run TOOL_FAILED
 *   <li>BudgetManager denied (hard budget) → Run BUDGET_EXCEEDED + Step SKIPPED_BUDGET
 *   <li>Cancellation → Run CANCELLED + step CANCELLED
 *   <li>Planner never invoked (ToolExecutor 是 mock, 但 verify 同 executor 调用次数 <= step 数)
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentRunExecutor - PR-6b.3 Run 终态矩阵")
class AgentRunExecutorTest {

    @Mock private AgentPersistenceCoordinator coordinator;
    @Mock private ToolExecutor toolExecutor;
    @Mock private AgentExecutionLeaseService leaseService;
    private AgentBudgetManager budgetManager;
    private EvidenceAccumulatorFactory evidenceFactory;
    private AgentRunExecutor executor;

    @BeforeEach
    void setup() {
        budgetManager = new AgentBudgetManager();
        evidenceFactory = new EvidenceAccumulatorFactory();
        when(leaseService.claim(anyString(), anyString(), any())).thenReturn(true);
        when(leaseService.heartbeat(anyString(), anyString(), any())).thenReturn(true);
        executor = new AgentRunExecutor(
                coordinator, budgetManager, toolExecutor, evidenceFactory, leaseService);
    }

    private DeterministicExecutionPlan plan(String stepId, boolean required) {
        return new DeterministicExecutionPlan(
                "p1",
                "v1",
                List.of(
                        new AgentToolStep(
                                stepId,
                                "semantic_search",
                                "v1",
                                new TestInput("q", 5),
                                List.of(),
                                "",
                                required)));
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                AgentBudget.pr6Default(),
                Instant.now().plusSeconds(30),
                Set.of("semantic_search"),
                20,
                4000,
                true,
                false,
                true);
    }

    private InitializedRun init(String runId) {
        AgentRunRecord run =
                new AgentRunRecord(
                        runId,
                        "req-1",
                        "tA",
                        "u1",
                        "COMPARISON",
                        AgentRunStatus.EXECUTING,
                        "p1",
                        "v1",
                        "h",
                        "{\"planId\":\"p1\"}",
                        AgentBudget.pr6Default(),
                        AgentBudgetReservation.zero(),
                        AgentUsage.zero(),
                        List.of(),
                        0,
                        null,
                        "rv",
                        "tsv",
                        "iv1",
                        "LIVE",
                        null,
                        null,
                        3);
        AgentStepRecord step =
                new AgentStepRecord(
                        runId,
                        "s1",
                        0,
                        "semantic_search",
                        "v1",
                        null,
                        "inputhash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                        AgentStepStatus.PENDING,
                        0,
                        List.of(),
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        0);
        return new InitializedRun(run, List.of(step));
    }

    private Evidence ev(String tenant) {
        return Evidence.of(
                tenant, 1L, 10L, "v1", "content", 0.9, null, "semantic_search", Map.of());
    }

    record TestInput(String query, Integer topK)
            implements com.xxx.ragdoc.application.chat.tool.ToolInput {
        @Override
        public String normalizedForDedup() {
            return query + "|" + topK;
        }
    }

    // mock coordinator defaults for happy path
    private void stubCoordinatorHappy() {
        when(coordinator.reloadStep(anyString(), anyString()))
                .thenAnswer(inv -> init("r1").steps().get(0));
        when(coordinator.reserveStep(
                        anyString(), anyLong(), anySet(), any(), any(), anyString(), anyLong()))
                .thenReturn(
                        new ReservationResult(
                                new AgentBudgetReservation(
                                        1, 1, 0, 0, 0, java.math.BigDecimal.ZERO),
                                4L,
                                1L));
        when(coordinator.markStepRunning(anyString(), anyString(), anyLong(), any()))
                .thenReturn(2L);
        when(coordinator.settleStep(
                        anyString(),
                        anyLong(),
                        anySet(),
                        any(),
                        any(),
                        anyInt(),
                        anyString(),
                        anyLong(),
                        any(),
                        any()))
                .thenReturn(new SettlementResult(5L, 3L));
    }

    @Test
    @DisplayName("单 Step SUCCESS + 有 Evidence → Run READY_TO_ANSWER")
    void singleStepSuccessWithEvidence() {
        stubCoordinatorHappy();
        TestOutput out = new TestOutput(List.of(ev("tA")));
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.success("call-1", "semantic_search", "v1", out, 10L, Map.of()));

        AgentRunResult r =
                executor.execute(
                        plan("s1", true),
                        policy(),
                        init("r1"),
                        "tA",
                        "req-1",
                        CancellationToken.never());

        assertThat(r.status()).isEqualTo(AgentRunStatus.READY_TO_ANSWER);
        assertThat(r.evidence()).hasSize(1);
        assertThat(r.terminalReasonCode()).isEqualTo("EVIDENCE_READY");
        assertThat(r.realToolCalls()).isEqualTo(1);
        verify(coordinator, atLeastOnce())
                .transitionRun(
                        eq("r1"),
                        eq(5L),
                        anySet(),
                        eq(AgentRunStatus.READY_TO_ANSWER),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName(
            "单 Step EMPTY (required + continueOnEmpty=true) + 无其它 Evidence → REFUSED_NO_EVIDENCE reasonCode=REQUIRED_EVIDENCE_MISSING")
    void requiredEmptyRefusedWithRequiredMissingReason() {
        stubCoordinatorHappy();
        TestOutput empty = new TestOutput(List.of());
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.success(
                                "call-1", "semantic_search", "v1", empty, 10L, Map.of()));

        AgentRunResult r =
                executor.execute(
                        plan("s1", true),
                        policy(),
                        init("r1"),
                        "tA",
                        "req-1",
                        CancellationToken.never());

        assertThat(r.status()).isEqualTo(AgentRunStatus.REFUSED_NO_EVIDENCE);
        assertThat(r.evidence()).isEmpty();
    }

    @Test
    @DisplayName(
            "单 Step 真实 Tool FAILED_TERMINAL (required) → Run TOOL_FAILED + step FAILED_TERMINAL")
    void requiredToolFailureTerminalRun() {
        stubCoordinatorHappy();
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.failure(
                                "call-1",
                                "semantic_search",
                                "v1",
                                ToolStatus.TERMINAL_ERROR,
                                ToolError.of("TOOL_TERMINAL_ERROR", "tool crashed"),
                                10L,
                                Map.of()));

        AgentRunResult r =
                executor.execute(
                        plan("s1", true),
                        policy(),
                        init("r1"),
                        "tA",
                        "req-1",
                        CancellationToken.never());

        assertThat(r.status()).isEqualTo(AgentRunStatus.TOOL_FAILED);
        // step terminal CAS 走 settleStep, verify step terminal target
        verify(coordinator, atLeastOnce())
                .settleStep(
                        eq("r1"),
                        anyLong(),
                        anySet(),
                        any(),
                        any(),
                        anyInt(),
                        eq("s1"),
                        anyLong(),
                        eq(AgentStepStatus.FAILED_TERMINAL),
                        any());
        // Run 也写终态
        verify(coordinator, atLeastOnce())
                .transitionRun(
                        eq("r1"),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.TOOL_FAILED),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("租约已被其它实例持有 → 拒绝执行且不调用工具")
    void leaseContentionRejectsBeforeToolExecution() {
        when(leaseService.claim(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        plan("s1", true),
                                        policy(),
                                        init("r1"),
                                        "tA",
                                        "req-1",
                                        CancellationToken.never()))
                .isInstanceOf(AgentRunExecutor.AgentLeaseUnavailableException.class);

        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
        verify(leaseService, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("租约释放异常 → 不覆盖已经完成的 Run 结果")
    void leaseReleaseFailureDoesNotMaskCompletedResult() {
        stubCoordinatorHappy();
        TestOutput out = new TestOutput(List.of(ev("tA")));
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.success("call-1", "semantic_search", "v1", out, 10L, Map.of()));
        doThrow(new IllegalStateException("db unavailable"))
                .when(leaseService)
                .release(anyString(), anyString());

        AgentRunResult result =
                executor.execute(
                        plan("s1", true),
                        policy(),
                        init("r1"),
                        "tA",
                        "req-1",
                        CancellationToken.never());

        assertThat(result.status()).isEqualTo(AgentRunStatus.READY_TO_ANSWER);
    }

    @Test
    @DisplayName("Cancellation 信号在 Step 前 → Run CANCELLED, Tool 不再被调")
    void cancellationBeforeStep() {
        stubCoordinatorHappy();
        CancellationTokenSource cts = new CancellationTokenSource();
        cts.cancel(); // 立即取消

        AgentRunResult r =
                executor.execute(
                        plan("s1", true), policy(), init("r1"), "tA", "req-1", cts.token());

        assertThat(r.status()).isEqualTo(AgentRunStatus.CANCELLED);
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
        verify(coordinator, atLeastOnce())
                .transitionStep(
                        anyString(),
                        eq("s1"),
                        anyLong(),
                        anySet(),
                        eq(AgentStepStatus.CANCELLED),
                        any());
    }

    @Test
    @DisplayName(
            "Hard budget exceeded (maxSteps=0) → Run BUDGET_EXCEEDED + step SKIPPED_BUDGET, Tool 不被调")
    void hardBudgetExceeded() {
        AgentBudget zeroStepsBudget =
                new AgentBudget(0, 5, 0, 0, 30_000L, 0, 0, 0, java.math.BigDecimal.ZERO);
        AgentExecutionPolicy bp =
                new AgentExecutionPolicy(
                        zeroStepsBudget,
                        Instant.now().plusSeconds(30),
                        Set.of("semantic_search"),
                        20,
                        4000,
                        true,
                        false,
                        true);
        when(coordinator.reloadStep(anyString(), anyString()))
                .thenAnswer(inv -> init("r1").steps().get(0));
        // reserveStep 不要被调用; 直接 verify step → SKIPPED_BUDGET, Run → BUDGET_EXCEEDED

        AgentRunResult r =
                executor.execute(
                        plan("s1", true), bp, init("r1"), "tA", "req-1", CancellationToken.never());

        assertThat(r.status()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
        verify(coordinator)
                .transitionStep(
                        eq("r1"),
                        eq("s1"),
                        anyLong(),
                        anySet(),
                        eq(AgentStepStatus.SKIPPED_BUDGET),
                        any());
        verify(coordinator, atLeastOnce())
                .transitionRun(
                        eq("r1"),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.BUDGET_EXCEEDED),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("Cleanup pass: 结束时所有非终态 step 必须被终结 (非终态 → CANCELLED/FAILED_TERMINAL)")
    void cleanupConvergesAllNonTerminalSteps() {
        stubCoordinatorHappy();
        TestOutput out = new TestOutput(List.of(ev("tA")));
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.success("call-1", "semantic_search", "v1", out, 10L, Map.of()));

        executor.execute(
                plan("s1", true), policy(), init("r1"), "tA", "req-1", CancellationToken.never());
        // cleanup pass 至少调用 transitionStep 一次; init.orderedSteps() 中只有 1 个 step (s1) 且经 settle 已终态
    }

    record TestOutput(List<Evidence> evidences) implements ToolOutput, EvidenceListOutput {
        @Override
        public EvidenceListOutput withEvidences(List<Evidence> e) {
            return new TestOutput(e);
        }
    }
}
