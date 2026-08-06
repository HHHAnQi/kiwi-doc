package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.CompletedStepSummary;
import java.util.List;
import java.util.Set;

/**
 * PR-7c / EMS-PR7 §2.3: 阶段执行的可变上下文。
 *
 * <p>同一 Run 跨 Phase 共享 usage / reservation / deadline / accumulator;
 * 每个 Phase 只追加新 Step (Replan 不重置任何 Run 字段)。
 *
 * <p><b>不变量</b> (Revision §10.x):
 *
 * <ul>
 *   <li>同一 {@code runId}; 同一 {@code AgentRunRecord}; 续作 Phase 不创建新 Run
 *   <li>{@code usage / reservation} 由前 Phase 输出传入; <b>不</b>从零开始
 *   <li>{@code accumulatedEvidenceIds} 接续上个 Phase; <b>不</b>清空
 *   <li>{@code usedToolSignatures} 单调增长; 用于 loop detection
 *   <li>{@code completedSteps} 用于 Replan Planner 输入 (PR-7c.2)
 *   <li>{@code phaseIndex} initial=0; 唯一允许的 Replan=1
 * </ul>
 */
public record PhaseExecutionContext(
        int phaseIndex,
        AgentUsage priorUsage,
        AgentBudgetReservation priorReservation,
        List<Evidence> priorEvidence,
        List<String> accumulatedEvidenceIds,
        List<CompletedStepSummary> completedSteps,
        Set<String> usedToolSignatures,
        /** 下一个可用的 agent_step.sequence (单 Run 内全局递增)。 */
        int nextStepSequence,
        /** 当前 Run.version (用于 CAS 续写)。 */
        long currentRunVersion,
        java.time.Instant runStartedAt,
        /** 当前 planId / planVersion (Replan 时升级; 但 Run 不重新初始化)。 */
        String currentPlanId,
        String currentPlanVersion) {

    public PhaseExecutionContext {
        if (priorUsage == null) priorUsage = AgentUsage.zero();
        if (priorReservation == null) priorReservation = AgentBudgetReservation.zero();
        priorEvidence = priorEvidence == null ? List.of() : List.copyOf(priorEvidence);
        accumulatedEvidenceIds =
                accumulatedEvidenceIds == null ? List.of() : List.copyOf(accumulatedEvidenceIds);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        usedToolSignatures = usedToolSignatures == null ? Set.of() : Set.copyOf(usedToolSignatures);
        if (phaseIndex < 0) phaseIndex = 0;
        if (nextStepSequence < 0) nextStepSequence = 0;
        if (currentPlanId == null) currentPlanId = "";
        if (currentPlanVersion == null) currentPlanVersion = "";
        if (runStartedAt == null) runStartedAt = java.time.Instant.now();
    }

    /** initial Phase context (replan 0): 空 continued state。 */
    public static PhaseExecutionContext initial(long runVersion, java.time.Instant runStartedAt) {
        return new PhaseExecutionContext(
                0,
                AgentUsage.zero(), AgentBudgetReservation.zero(),
                List.of(), List.of(), List.of(), Set.of(),
                0, runVersion, runStartedAt, "", "");
    }
}
