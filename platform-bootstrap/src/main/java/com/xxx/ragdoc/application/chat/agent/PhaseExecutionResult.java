package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.CompletedStepSummary;
import java.util.List;
import java.util.Set;

/**
 * PR-7c / EMS-PR7 §2.3: 单 Phase 执行结果 (KEEP_EXECUTING 模式)。
 *
 * <p>PlannedAgentPipeline 据此构造 {@code SufficiencyRequest}; {@link AgentRunPhaseExecutor}
 * 在 KEEP_EXECUTING 模式<b>不</b>写终态 CAS。
 *
 * <p>关键字段:
 *
 * <ul>
 *   <li>{@code executedStepIds}: 本 Phase 实际执行的 step ID (供 Trace)。
 *   <li>{@code newEvidence}: 本 Phase 新增的 Evidence (排除来自 prior phase 的)。
 *   <li>{@code accumulatedEvidence}: 截至本 Phase 累积全部 Evidence (供 Sufficiency / Answer Composer)。
 *   <li>{@code usage} / {@code reservation}: 当前 Run 余额 (continuation 时使用)。
 *   <li>{@code completedSteps}: safe summaries, 用于 Replan Planner 输入。
 *   <li>{@code usedToolSignatures}: 历史签名集合 (loop detection)。
 *   <li>{@code discoveredEntities}: 本 Phase 新出现的实体 (用于派生 Requirement)。
 *   <li>{@code requiredStepFailed}: 本 Phase required Step 是否终态失败 → 终止 Replan, 转 TOOL_FAILED。
 *   <li>{@code failureReasonCode}: 失败短代码 (污染预算/超时等) — null 表示无 failure。
 *   <li>{@code prematureTerminal}: 若 Phase 内已转 Run 终态 (Cancel/Timeout/Budget/Conflict),
 *       Pipeline 不再调 Sufficiency。
 * </ul>
 */
public record PhaseExecutionResult(
        String runId,
        int phaseIndex,
        long latestRunVersion,
        List<String> executedStepIds,
        List<Evidence> newEvidence,
        List<Evidence> accumulatedEvidence,
        AgentUsage usage,
        AgentBudgetReservation reservation,
        List<CompletedStepSummary> completedSteps,
        Set<String> usedToolSignatures,
        Set<String> discoveredEntities,
        boolean requiredStepFailed,
        String failureReasonCode,
        AgentRunStatus prematureTerminal) {

    public PhaseExecutionResult {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        executedStepIds = executedStepIds == null ? List.of() : List.copyOf(executedStepIds);
        newEvidence = newEvidence == null ? List.of() : List.copyOf(newEvidence);
        accumulatedEvidence = accumulatedEvidence == null ? List.of() : List.copyOf(accumulatedEvidence);
        if (usage == null) usage = AgentUsage.zero();
        if (reservation == null) reservation = AgentBudgetReservation.zero();
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        usedToolSignatures = usedToolSignatures == null ? Set.of() : Set.copyOf(usedToolSignatures);
        discoveredEntities = discoveredEntities == null ? Set.of() : Set.copyOf(discoveredEntities);
        if (failureReasonCode == null) failureReasonCode = "";
    }

    public boolean hasPrematureTerminal() {
        return prematureTerminal != null;
    }

    public boolean hasBusinessFailure() {
        return failureReasonCode != null && !failureReasonCode.isBlank();
    }
}
