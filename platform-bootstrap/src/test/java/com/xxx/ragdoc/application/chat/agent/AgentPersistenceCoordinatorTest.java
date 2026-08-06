package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.ReservationResult;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR-6b.1: {@link AgentPersistenceCoordinator} 单测 (mock Ports; @Transactional 真实 behavior 由
 * AgentRunExecutorToolTxIT 在 PR-6b.3 用 Testcontainers + SpringBootTest 验证)。
 *
 * <p>重点: Reservation 双 CAS / Settlement 双 CAS 任一失败必须抛 {@link AgentCasConflictException};
 * markStepRunning 必须<b>只</b>调 stepRepository.transition (禁止再做 Run CAS); initializeRun 整体回滚
 * 已由 Spring `@Transactional REQUIRES_NEW` (本单测只验证抛出 + 不发生后续 CAS)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentPersistenceCoordinator - PR-6b 多 CAS 事务协调")
class AgentPersistenceCoordinatorTest {

    @Mock private AgentRunRepository runRepo;
    @Mock private AgentStepRepository stepRepo;
    private AgentPersistenceCoordinator coord;
    private final AgentBudgetManager budgetMgr = new AgentBudgetManager();

    @BeforeEach
    void setup() {
        coord = new AgentPersistenceCoordinator(runRepo, stepRepo);
    }

    private AgentRunRecord runAt(String runId, long version, AgentRunStatus status) {
        return new AgentRunRecord(
                runId, "req-1", "tenant-A", "user-1", "COMPARISON",
                status, "plan-1", "v1",
                "fakehash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                "{\"planId\":\"plan-1\"}",
                AgentBudget.pr6Default(), AgentBudgetReservation.zero(), AgentUsage.zero(),
                List.of(), 0, null, "rule-v1", "toolset-v1", "iv-1", "LIVE",
                null, null, version);
    }

    private AgentStepRecord stepAt(String runId, String stepId, int seq, AgentStepStatus status, long version) {
        return new AgentStepRecord(
                runId, stepId, seq,
                "semantic_search", "v1", null,
                "inputhash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                status, 0, List.of(),
                null, null, false, false, false,
                null, null, null, null, version);
    }

    // ─── initializeRunAndSteps ─────────────────────────

    @Nested
    @DisplayName("initializeRunAndSteps: 单事务原子创建 + 三次 CAS")
    class Init {

        @Test
        @DisplayName("正常: create run + create 2 steps + 三次 CAS RECEIVED→ROUTED→PLANNED→EXECUTING")
        void happyPath() {
            AgentRunRecord run = runAt("r1", 0, AgentRunStatus.RECEIVED);
            List<AgentStepRecord> steps = List.of(
                    stepAt("r1", "s1", 1, AgentStepStatus.PENDING, 0),
                    stepAt("r1", "s2", 2, AgentStepStatus.PENDING, 0));
            when(runRepo.create(any())).thenReturn(run);
            when(stepRepo.create(any())).thenAnswer(inv -> inv.getArgument(0));
            when(runRepo.findByRunId("r1"))
                    .thenReturn(Optional.of(runAt("r1", 1, AgentRunStatus.ROUTED)))
                    .thenReturn(Optional.of(runAt("r1", 2, AgentRunStatus.PLANNED)))
                    .thenReturn(Optional.of(runAt("r1", 3, AgentRunStatus.EXECUTING)));
            when(stepRepo.findByRunIdAndStepId("r1", "s1"))
                    .thenReturn(Optional.of(steps.get(0)));
            when(stepRepo.findByRunIdAndStepId("r1", "s2"))
                    .thenReturn(Optional.of(steps.get(1)));
            when(runRepo.transition(any(), anyLong(), any(), any(), any(), any(), any()))
                    .thenReturn(true);

            InitializedRun ir = coord.initializeRunAndSteps(run, steps);

            assertThat(ir.run().status()).isEqualTo(AgentRunStatus.EXECUTING);
            assertThat(ir.run().version()).isEqualTo(3);
            // 三次 CAS: RECEIVED→ROUTED / ROUTED→PLANNED / PLANNED→EXECUTING
            verify(runRepo, times(3)).transition(any(), anyLong(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("create run 失败 → AgentRunInitializationException, 不再写 step / CAS")
        void createRunFails() {
            AgentRunRecord run = runAt("r1", 0, AgentRunStatus.RECEIVED);
            when(runRepo.create(any())).thenThrow(new RuntimeException("dup pk"));

            assertThatThrownBy(() -> coord.initializeRunAndSteps(run,
                    List.of(stepAt("r1", "s1", 1, AgentStepStatus.PENDING, 0))))
                    .isInstanceOf(AgentRunInitializationException.class);

            verify(stepRepo, never()).create(any());
            verify(runRepo, never()).transition(any(), anyLong(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("第三次 CAS 失败 → AgentRunInitializationException, 不再 reload")
        void firstCasFails() {
            AgentRunRecord run = runAt("r1", 0, AgentRunStatus.RECEIVED);
            List<AgentStepRecord> steps = List.of(stepAt("r1", "s1", 1, AgentStepStatus.PENDING, 0));
            when(runRepo.create(any())).thenReturn(run);
            when(stepRepo.create(any())).thenAnswer(inv -> inv.getArgument(0));
            when(runRepo.transition(any(), anyLong(), any(), any(), any(), any(), any()))
                    .thenReturn(true)
                    .thenReturn(true)
                    .thenReturn(false);  // 第三次 PLANNED→EXECUTING CAS 失败
            when(runRepo.findByRunId("r1"))
                    .thenReturn(Optional.of(runAt("r1", 1, AgentRunStatus.ROUTED)))
                    .thenReturn(Optional.of(runAt("r1", 2, AgentRunStatus.PLANNED)));

            assertThatThrownBy(() -> coord.initializeRunAndSteps(run, steps))
                    .isInstanceOf(AgentRunInitializationException.class)
                    .hasMessageContaining("PLANNED");
        }
    }

    // ─── reserveStep ───────────────────────────────

    @Nested
    @DisplayName("reserveStep: 双 CAS 原子 (Revision §2)")
    class Reserve {

        @Test
        @DisplayName("正常: updateBudgetState + step PENDING→RESERVED 都成功")
        void happy() {
            when(runRepo.updateBudgetState(any(), anyLong(), any(), any(), any())).thenReturn(true);
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);

            ReservationResult r = coord.reserveStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING), AgentUsage.zero(),
                    new BudgetDecision.Allowed(new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO)),
                    "s1", 0);

            assertThat(r.newRunVersion()).isEqualTo(4);
            assertThat(r.newStepVersion()).isEqualTo(1);
            assertThat(r.newReservation().reservedSteps()).isEqualTo(1);
        }

        @Test
        @DisplayName("Run reservation CAS 失败 → 抛 + stepRepository.transition 不被调用 (回滚)")
        void runCasFailsSkipsStepWrite() {
            when(runRepo.updateBudgetState(any(), anyLong(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> coord.reserveStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING), AgentUsage.zero(),
                    new BudgetDecision.Allowed(new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO)),
                    "s1", 0))
                    .isInstanceOf(AgentCasConflictException.class)
                    .hasMessageContaining("RUN_RESERVATION");

            verify(stepRepo, never()).transition(any(), any(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Step PENDING→RESERVED CAS 失败 → 抛 (Revision §2 整体回滚)")
        void stepCasFails() {
            when(runRepo.updateBudgetState(any(), anyLong(), any(), any(), any())).thenReturn(true);
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> coord.reserveStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING), AgentUsage.zero(),
                    new BudgetDecision.Allowed(new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO)),
                    "s1", 0))
                    .isInstanceOf(AgentCasConflictException.class)
                    .hasMessageContaining("STEP_RESERVE");
        }
    }

    // ─── markStepRunning ───────────────────────────

    @Nested
    @DisplayName("markStepRunning: 仅 step CAS, 禁止再写 Run (Revision §3)")
    class MarkRunning {

        @Test
        @DisplayName("正常: step RESERVED→RUNNING CAS 成功, 返回新 version")
        void happy() {
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);

            long newStepVer = coord.markStepRunning(
                    "r1", "s1", 1, AgentStepUpdate.empty());

            assertThat(newStepVer).isEqualTo(2);
            verify(runRepo, never()).updateBudgetState(any(), anyLong(), any(), any(), any());
            verify(runRepo, never()).transition(any(), anyLong(), any(), any(), any(), any(), any());
            verify(runRepo, never()).settleRunStep(any(), anyLong(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("CAS 失败 → 抛 AgentCasConflictException(STEP_MARK_RUNNING)")
        void casFail() {
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> coord.markStepRunning("r1", "s1", 1, AgentStepUpdate.empty()))
                    .isInstanceOf(AgentCasConflictException.class)
                    .hasMessageContaining("STEP_MARK_RUNNING");
        }
    }

    // ─── settleStep ───────────────────────────────────────

    @Nested
    @DisplayName("settleStep: 合并 run CAS + step terminal CAS (Revision §4)")
    class Settle {

        @Test
        @DisplayName("正常: 合并 run settleRunStep CAS + step terminal CAS")
        void happy() {
            when(runRepo.settleRunStep(any(), anyLong(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(true);
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);

            SettlementResult s = coord.settleStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING),
                    budgetMgr.settle(
                            AgentUsage.zero(), new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO),
                            StepSettlement.realTool(AgentStepStatus.SUCCEEDED, "", 0, 0, java.math.BigDecimal.ZERO)),
                    List.of("ev-1"), 1,
                    "s1", 2, AgentStepStatus.SUCCEEDED, AgentStepUpdate.empty());

            assertThat(s.newRunVersion()).isEqualTo(4);
            assertThat(s.newStepVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("合并 Run CAS 失败 → 抛 + step terminal 不再发生 (Revision §4 整体回滚)")
        void runCasFailSkipsStepTerminal() {
            when(runRepo.settleRunStep(any(), anyLong(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(false);

            assertThatThrownBy(() -> coord.settleStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING),
                    budgetMgr.settle(
                            AgentUsage.zero(), new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO),
                            StepSettlement.realTool(AgentStepStatus.SUCCEEDED, "", 0, 0, java.math.BigDecimal.ZERO)),
                    List.of("ev-1"), 1,
                    "s1", 2, AgentStepStatus.SUCCEEDED, AgentStepUpdate.empty()))
                    .isInstanceOf(AgentCasConflictException.class)
                    .hasMessageContaining("RUN_SETTLE");

            verify(stepRepo, never()).transition(any(), any(), anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Step terminal CAS 失败 → 抛 (Revision §4)")
        void stepCasFail() {
            when(runRepo.settleRunStep(any(), anyLong(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(true);
            when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> coord.settleStep(
                    "r1", 3, Set.of(AgentRunStatus.EXECUTING),
                    budgetMgr.settle(
                            AgentUsage.zero(), new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO),
                            StepSettlement.realTool(AgentStepStatus.SUCCEEDED, "", 0, 0, java.math.BigDecimal.ZERO)),
                    List.of("ev-1"), 1,
                    "s1", 2, AgentStepStatus.SUCCEEDED, AgentStepUpdate.empty()))
                    .isInstanceOf(AgentCasConflictException.class)
                    .hasMessageContaining("STEP_TERMINATE");
        }
    }

    @Test
    @DisplayName("transitionStep (无 Run 写, 用于 cleanup PENDING → CANCELLED)")
    void transitionStepOnly() {
        when(stepRepo.transition(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);

        boolean ok = coord.transitionStep(
                "r1", "s2", 0,
                Set.of(AgentStepStatus.PENDING),
                AgentStepStatus.CANCELLED,
                AgentStepUpdate.empty());

        assertThat(ok).isTrue();
        verify(runRepo, never()).transition(any(), anyLong(), any(), any(), any(), any(), any());
    }
}
