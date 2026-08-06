package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR-6b.1 / EMS-PR6 §11: Agent Run/Step 持久化事务协调器。
 *
 * <p>Revision §2 §3 §4 关键约束:
 *
 * <ol>
 *   <li>多 CAS 事务内任一 CAS 失败 → 抛 {@link AgentCasConflictException}, 让
 *       Spring `@Transactional REQUIRES_NEW` 整体回滚。禁止半提交 (Reservation CAS ok 但 Step CAS fail)。
 *   <li>禁止用 settleStep 完成 RESERVED → RUNNING — 三方法职责清晰分离:
 *       <ul>
 *         <li>{@link #reserveStep}: run reservation CAS + step PENDING → RESERVED (单事务)
 *         <li>{@link #markStepRunning}: step RESERVED → RUNNING (单事务, 独立短)
 *         <li>{@link #settleStep}: <b>合并</b> run CAS (usage+reservation+evidenceIds 一次) + step terminal CAS (单事务)
 *       </ul>
 *   <li>{@link #initializeRunAndSteps}: 单一事务 — 创建 run + 全部 step + 三次状态 CAS; 任一失败回滚
 *       (Revision §1 §9)。不在此处调用任何远程 IO。
 * </ol>
 *
 * <p>Executor 在每段事务<b>外</b>调用 {@code ToolExecutor} (Milvus/Sparse/Harness), 远程调用禁止进入本类事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPersistenceCoordinator {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;

    // ─── 1. 初始化 ───────────────────────────────────────────

    /**
     * 单事务原子创建: Run + 所有 Steps + 三次 CAS (RECEIVED → ROUTED → PLANNED → EXECUTING)。
     *
     * <p>失败抛 {@link AgentRunInitializationException}, Spring 已回滚事务, 无遗留中间状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public InitializedRun initializeRunAndSteps(
            AgentRunRecord run,
            List<AgentStepRecord> steps /* 按 PlanValidator.topologicalStepOrder 已排序 */) {
        AgentRunRecord created;
        try {
            created = runRepository.create(run);
        } catch (RuntimeException ex) {
            throw new AgentRunInitializationException(
                    run.runId(), "create agent_run 失败: " + ex.getMessage(), ex);
        }
        // 创建所有 steps
        for (AgentStepRecord step : steps) {
            try {
                stepRepository.create(step);
            } catch (RuntimeException ex) {
                throw new AgentRunInitializationException(
                        run.runId(),
                        "create agent_step 失败 stepId=" + step.stepId() + ": " + ex.getMessage(),
                        ex);
            }
        }
        // 三次 CAS: RECEIVED → ROUTED → PLANNED → EXECUTING
        casRunOrInitFailed(run.runId(), run.version() /* 0 */, Set.of(AgentRunStatus.RECEIVED),
                AgentRunStatus.ROUTED, "ROUTED", run.usage(), run.reservation());
        AgentRunRecord routed = reload(run.runId());
        casRunOrInitFailed(run.runId(), routed.version(), Set.of(AgentRunStatus.ROUTED),
                AgentRunStatus.PLANNED, "PLANNED", routed.usage(), routed.reservation());
        AgentRunRecord planned = reload(run.runId());
        casRunOrInitFailed(run.runId(), planned.version(), Set.of(AgentRunStatus.PLANNED),
                AgentRunStatus.EXECUTING, "INITIALIZED", planned.usage(), planned.reservation());
        AgentRunRecord executing = reload(run.runId());
        List<AgentStepRecord> refreshing = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            AgentStepRecord s = steps.get(i);
            // steps 不会被并发改写, 但 version 一定为 0
            AgentStepRecord fresh =
                    stepRepository.findByRunIdAndStepId(run.runId(), s.stepId())
                            .orElseThrow(() -> new AgentRunInitializationException(
                                    run.runId(), "刚创建的 step 不可读: " + s.stepId()));
            refreshing.add(fresh);
        }
        return new InitializedRun(executing, refreshing);
    }

    private void casRunOrInitFailed(
            String runId, long expectedVersion, Set<AgentRunStatus> expectedStatuses,
            AgentRunStatus target, String reasonCode,
            AgentUsage usage, AgentBudgetReservation reservation) {
        boolean ok = runRepository.transition(
                runId, expectedVersion, expectedStatuses, target, reasonCode, usage, reservation);
        if (!ok) {
            throw new AgentRunInitializationException(
                    runId,
                    "Run CAS 失败: " + expectedStatuses + " → " + target
                            + " version=" + expectedVersion);
        }
    }

    private AgentRunRecord reload(String runId) {
        return runRepository.findByRunId(runId)
                .orElseThrow(() -> new AgentRunInitializationException(
                        runId, "刚创建 / CAS 的 run 立刻不可读"));
    }

    /** PR-7c.3: Finalizer / Pipeline 在 CAS 失败后重读当前 Run 状态 (Optional.empty 表示 run 不可读)。 */
    public java.util.Optional<AgentRunRecord> reloadRun(String runId) {
        return runRepository.findByRunId(runId);
    }

    /** PR-7c.3c-2: 在同一 Run 内追加 Replan Steps (单一短事务原子; 兼 UNIQUE 冲突回滚)。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void appendReplanSteps(String runId, List<AgentStepRecord> steps) {
        try {
            stepRepository.appendAll(runId, steps);
        } catch (RuntimeException ex) {
            throw new AgentRunInitializationException(
                    runId, "appendReplanSteps 失败: " + ex.getMessage(), ex);
        }
    }

    /** Executor 在 step 主循环reload最新 step 状态 (无写)。 */
    public AgentStepRecord reloadStep(String runId, String stepId) {
        return stepRepository.findByRunIdAndStepId(runId, stepId)
                .orElseThrow(() -> new IllegalStateException(
                        "agent_step 不可读 run=" + runId + " step=" + stepId));
    }

    // ─── 2. 预留 ────────────────────────────────────────────────

    /**
     * 单事务原子: Run reservation CAS (updateBudgetState) + Step PENDING → RESERVED (transition)。
     *
     * <p>任一 CAS 失败 → 抛 {@link AgentCasConflictException} + 自动回滚, 禁止半提交 (Revision §2)。
     *
     * @param decisionAllowed BudgetManager.evaluate 返回的 {@link BudgetDecision.Allowed},
     *                        调用方对 Allowed 之外的Denied不应进入本方法 (Denied 直接 SKIPPED_BUDGET)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReservationResult reserveStep(
            String runId, long runVersion, Set<AgentRunStatus> expectedRunStatuses,
            AgentUsage currentUsage, BudgetDecision.Allowed decisionAllowed,
            String stepId, long stepVersion) {
        AgentBudgetReservation newReservation = decisionAllowed.newReservation();
        boolean runOk = runRepository.updateBudgetState(
                runId, runVersion, expectedRunStatuses, currentUsage, newReservation);
        if (!runOk) {
            throw new AgentCasConflictException(
                    AgentCasConflictException.Side.RUN_RESERVATION,
                    "run updateBudgetState CAS 失败 run=" + runId + " version=" + runVersion);
        }
        boolean stepOk = stepRepository.transition(
                runId, stepId, stepVersion,
                Set.of(AgentStepStatus.PENDING),
                AgentStepStatus.RESERVED,
                AgentStepUpdate.empty());
        if (!stepOk) {
            throw new AgentCasConflictException(
                    AgentCasConflictException.Side.STEP_RESERVE,
                    "step PENDING→RESERVED CAS 失败 run=" + runId + " step=" + stepId);
        }
        return new ReservationResult(newReservation, runVersion + 1, stepVersion + 1);
    }

    // ─── 3. 标记 RUNNING ────────────────────────────────────

    /**
     * 单事务短 CAS: Step RESERVED → RUNNING。Revision §3 — 禁止用 settleStep 完成此转换。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public long markStepRunning(String runId, String stepId, long stepVersion, AgentStepUpdate update) {
        boolean ok = stepRepository.transition(
                runId, stepId, stepVersion,
                Set.of(AgentStepStatus.RESERVED),
                AgentStepStatus.RUNNING,
                update);
        if (!ok) {
            throw new AgentCasConflictException(
                    AgentCasConflictException.Side.STEP_MARK_RUNNING,
                    "step RESERVED→RUNNING CAS 失败 run=" + runId + " step=" + stepId);
        }
        return stepVersion + 1;
    }

    // ─── 4. 结算 ────────────────────────────────────────────

    /**
     * 单事务原子: <b>合并</b> run CAS (settleRunStep: usage+reservation+evidenceIds 一次, Revision §4)
     * + step terminal CAS (RUNNING → 终态)。
     *
     * <p>Tool / Harness / 远程调用<b>已</b>在调用前完成 — 本方法仅写库。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public SettlementResult settleStep(
            String runId, long runVersion, Set<AgentRunStatus> expectedRunStatuses,
            AgentBudgetManager.SettleResult settleResult,
            List<String> evidenceIdsAfterThisStep /* 整 Run 至此累积的全部 evidence IDs, 非增量 */,
            int evidenceCountAfterThisStep,
            String stepId, long stepVersion,
            AgentStepStatus terminalStepStatus,
            AgentStepUpdate stepUpdate) {
        // 1. 合并 run CAS: usage + reservation + evidenceIds 一次推进 version+1
        boolean runOk = runRepository.settleRunStep(
                runId, runVersion, expectedRunStatuses,
                settleResult.newUsage(),
                settleResult.newReservation(),
                evidenceIdsAfterThisStep,
                evidenceCountAfterThisStep);
        if (!runOk) {
            throw new AgentCasConflictException(
                    AgentCasConflictException.Side.RUN_SETTLE,
                    "run settleRunStep CAS 失败 run=" + runId + " version=" + runVersion);
        }
        // 2. step terminal CAS: RUNNING → terminal状态
        boolean stepOk = stepRepository.transition(
                runId, stepId, stepVersion,
                Set.of(AgentStepStatus.RUNNING),
                terminalStepStatus,
                stepUpdate);
        if (!stepOk) {
            throw new AgentCasConflictException(
                    AgentCasConflictException.Side.STEP_TERMINATE,
                    "step RUNNING→" + terminalStepStatus + " CAS 失败 run=" + runId + " step=" + stepId);
        }
        return new SettlementResult(runVersion + 1, stepVersion + 1);
    }

    // ─── 5. Skip / Cancel 简单写 ─────────────────────────────

    /**
     * 直接 step terminal CAS (无 reservation 写, 如 SKIPPED_BUDGET / SKIPPED_DUPLICATE / CANCELLED-from-PENDING)。
     *
     * <p>无 Run 写; Executor 外层在 hard budget 时另写 Run BUDGET_EXCEEDED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean transitionStep(
            String runId, String stepId, long stepVersion,
            Set<AgentStepStatus> expectedStatuses, AgentStepStatus target, AgentStepUpdate update) {
        boolean ok = stepRepository.transition(runId, stepId, stepVersion, expectedStatuses, target, update);
        if (!ok) {
            log.warn("agent_step direct transition CAS 失败 run={} step={} {}→{}",
                    runId, stepId, expectedStatuses, target);
        }
        return ok;
    }

    /**
     * 直接 run terminal CAS 用于 cleanup (EXECUTING/READY → 失败终态)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean transitionRun(
            String runId, long runVersion, Set<AgentRunStatus> expected, AgentRunStatus target,
            String reasonCode, AgentUsage usage, AgentBudgetReservation reservation) {
        return runRepository.transition(runId, runVersion, expected, target, reasonCode, usage, reservation);
    }

    // ─── 结果 records ────────────────────────────────────

    public record InitializedRun(AgentRunRecord run, List<AgentStepRecord> steps) {
        public InitializedRun {
            steps = List.copyOf(steps);
        }
    }

    public record ReservationResult(
            AgentBudgetReservation newReservation,
            long newRunVersion,
            long newStepVersion) {}

    public record SettlementResult(long newRunVersion, long newStepVersion) {}
}
