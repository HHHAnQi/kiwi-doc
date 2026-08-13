package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.ReservationResult;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource.CancellationToken;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.tool.EvidenceListOutput;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
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
 * PR-7c.1: {@link AgentRunPhaseExecutor} 关键不变量测试。
 *
 * <p>重点断言 (§17.1):
 *
 * <ul>
 *   <li>执行后 Run 保持 EXECUTING (Phase 不写终态 / 不调 transitionRun)
 *   <li>新 Evidence 在 result.newEvidence 返回
 *   <li>Usage/Reservation 延续 (initial-zero + Phase 内增量)
 *   <li>required Step FAILED_TERMINAL → prematureTerminal = TOOL_FAILED, hasBusinessFailure
 *   <li>cancel → prematureTerminal = CANCELLED, Tool 未调
 *   <li>budget_denied → prematureTerminal = BUDGET_EXCEEDED
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentRunPhaseExecutor - PR-7c.1 KEEP_EXECUTING 阶段执行")
class AgentRunPhaseExecutorTest {

    @Mock private AgentPersistenceCoordinator coordinator;
    @Mock private ToolExecutor toolExecutor;
    private AgentBudgetManager budgetManager;
    private EvidenceAccumulatorFactory evidenceFactory;
    private AgentRunPhaseExecutor phaseExecutor;

    private record TestOutput(List<Evidence> evidences) implements ToolOutput, EvidenceListOutput {
        @Override
        public EvidenceListOutput withEvidences(List<Evidence> e) {
            return new TestOutput(e);
        }
    }

    @BeforeEach
    void setup() {
        budgetManager = new AgentBudgetManager();
        evidenceFactory = new EvidenceAccumulatorFactory();
        phaseExecutor =
                new AgentRunPhaseExecutor(
                        coordinator, budgetManager, toolExecutor, evidenceFactory);
    }

    private AgentRunHandle handleWithStatus(AgentRunStatus status) {
        AgentRunRecord run =
                new AgentRunRecord(
                        "r1",
                        "req-1",
                        "tA",
                        "u1",
                        "MULTI_HOP",
                        status,
                        "plan-1",
                        "v1",
                        "h",
                        "{}",
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
        AgentStepRecord s1 =
                new AgentStepRecord(
                        "r1",
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
        return new AgentRunHandle(
                run,
                List.of(s1),
                new DeterministicExecutionPlan(
                        "plan-1",
                        "v1",
                        List.of(
                                new AgentToolStep(
                                        "s1",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        "expected",
                                        true))),
                AgentExecutionPolicy.pr6Default(),
                Instant.now().plusSeconds(60),
                CancellationToken.never(),
                "tA",
                java.time.Clock.systemUTC());
    }

    private void stubCoordinatorHappy() {
        when(coordinator.reloadStep(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                new AgentStepRecord(
                                        "r1",
                                        "s1",
                                        0,
                                        "semantic_search",
                                        "v1",
                                        null,
                                        "inputhash",
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
                                        0));
        when(coordinator.reserveStep(
                        anyString(),
                        anyLong(),
                        anySet(),
                        any(),
                        any(BudgetDecision.Allowed.class),
                        anyString(),
                        anyLong()))
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
    @DisplayName(
            "单 Step 成功 → result.newEvidence 非空; coordinator.transitionRun 未被调用 (Run 仍 EXECUTING)")
    void successKeepsExecuting() {
        stubCoordinatorHappy();
        Evidence ev =
                Evidence.of(
                        "tA",
                        1L,
                        10L,
                        "v1",
                        "evidence content",
                        0.9,
                        null,
                        "semantic_search",
                        Map.of());
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.success(
                                "c1",
                                "semantic_search",
                                "v1",
                                new TestOutput(List.of(ev)),
                                10L,
                                Map.of()));

        AgentRunHandle handle = handleWithStatus(AgentRunStatus.EXECUTING);
        PhaseExecutionResult r =
                phaseExecutor.executePhase(
                        handle,
                        handle.plan().steps(),
                        Set.of(),
                        Map.of("R1", "s1"),
                        PhaseExecutionContext.initial(3L, Instant.now()),
                        CancellationToken.never());

        assertThat(r.prematureTerminal()).isNull();
        assertThat(r.newEvidence()).hasSize(1);
        assertThat(r.usage().usedToolCalls()).isEqualTo(1);
        assertThat(r.executedStepIds()).containsExactly("s1");
        verify(coordinator, never())
                .transitionRun(anyString(), anyLong(), anySet(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("cancel 信号前置 → prematureTerminal=CANCELLED, ToolExecutor 不被调")
    void cancellationSkipsTool() {
        stubCoordinatorHappy();
        CancellationTokenSource cts = new CancellationTokenSource();
        cts.cancel();
        AgentRunHandle handle = handleWithStatus(AgentRunStatus.EXECUTING);

        PhaseExecutionResult r =
                phaseExecutor.executePhase(
                        handle,
                        handle.plan().steps(),
                        Set.of(),
                        Map.of(),
                        PhaseExecutionContext.initial(3L, Instant.now()),
                        cts.token());

        assertThat(r.prematureTerminal()).isEqualTo(AgentRunStatus.CANCELLED);
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Budget zero → prematureTerminal=BUDGET_EXCEEDED, Tool 未调, Step 转 SKIPPED_BUDGET")
    void hardBudgetExceeded() {
        AgentBudget zero = new AgentBudget(0, 0, 0, 0, 30_000L, 0, 0, 0, java.math.BigDecimal.ZERO);
        AgentRunHandle handle = budgetZeroHandle(zero);
        when(coordinator.reloadStep(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                new AgentStepRecord(
                                        "r1",
                                        "s1",
                                        0,
                                        "semantic_search",
                                        "v1",
                                        null,
                                        "inputhash",
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
                                        0));
        when(coordinator.transitionStep(
                        anyString(),
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentStepStatus.SKIPPED_BUDGET),
                        any()))
                .thenReturn(true);

        PhaseExecutionResult r =
                phaseExecutor.executePhase(
                        handle,
                        handle.plan().steps(),
                        Set.of(),
                        Map.of(),
                        PhaseExecutionContext.initial(3L, Instant.now()),
                        CancellationToken.never());

        assertThat(r.prematureTerminal()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        verify(toolExecutor, never()).execute(anyString(), anyString(), any(), any());
        verify(coordinator, times(1))
                .transitionStep(
                        anyString(),
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentStepStatus.SKIPPED_BUDGET),
                        any());
    }

    @Test
    @DisplayName(
            "required Tool FAILED_TERMINAL → prematureTerminal=TOOL_FAILED; requiredStepFailed=true")
    void requiredToolFailedTerminal() {
        stubCoordinatorHappy();
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        ToolResult.failure(
                                "c1",
                                "semantic_search",
                                "v1",
                                ToolStatus.TERMINAL_ERROR,
                                ToolError.of("TOOL_TERMINAL", "tool crashed"),
                                5L,
                                Map.of()));
        AgentRunHandle handle = handleWithStatus(AgentRunStatus.EXECUTING);

        PhaseExecutionResult r =
                phaseExecutor.executePhase(
                        handle,
                        handle.plan().steps(),
                        Set.of(),
                        Map.of("R1", "s1"),
                        PhaseExecutionContext.initial(3L, Instant.now()),
                        CancellationToken.never());

        assertThat(r.prematureTerminal()).isEqualTo(AgentRunStatus.TOOL_FAILED);
        assertThat(r.requiredStepFailed()).isTrue();
    }

    @Test
    @DisplayName(
            "续作 Phase: PhaseExecutionContext.initial → Phase 0; usage 从 zero 开始 (PR-7c.3 Pipeline 持续 phase-to-phase)")
    void initialPhaseContextZeroBudget() {
        PhaseExecutionContext ctx = PhaseExecutionContext.initial(7L, Instant.now());
        assertThat(ctx.phaseIndex()).isZero();
        assertThat(ctx.priorUsage().usedSteps()).isZero();
        assertThat(ctx.priorReservation().reservedSteps()).isZero();
        assertThat(ctx.usedToolSignatures()).isEmpty();
        assertThat(ctx.nextStepSequence()).isZero();
    }

    private AgentRunHandle budgetZeroHandle(AgentBudget budget) {
        AgentRunRecord run =
                new AgentRunRecord(
                        "r1",
                        "req-1",
                        "tA",
                        "u1",
                        "MULTI_HOP",
                        AgentRunStatus.EXECUTING,
                        "p1",
                        "v1",
                        "h",
                        "{}",
                        budget,
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
        AgentStepRecord s1 =
                new AgentStepRecord(
                        "r1",
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
        AgentExecutionPolicy p =
                new AgentExecutionPolicy(
                        budget,
                        Instant.now().plusSeconds(60),
                        Set.of("semantic_search"),
                        20,
                        4000,
                        true,
                        false,
                        true);
        return new AgentRunHandle(
                run,
                List.of(s1),
                new DeterministicExecutionPlan(
                        "p1",
                        "v1",
                        List.of(
                                new AgentToolStep(
                                        "s1",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        "expected",
                                        true))),
                p,
                Instant.now().plusSeconds(60),
                CancellationToken.never(),
                "tA",
                java.time.Clock.systemUTC());
    }
}
