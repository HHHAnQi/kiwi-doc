package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7c.2 / EMS-PR7 §10 + §11 + §7: 决策是否允许触发<b>唯一一次</b> Replan。
 *
 * <p>调用方: {@code PlannedAgentPipeline} 在 Phase 0 执行 + Sufficiency 判定后调用本组件。
 *
 * <p>唯一允许 Replan 的条件 (Revision §10.1 全部满足):
 *
 * <ol>
 *   <li>{@code sufficiency.status} ∈ {INSUFFICIENT, UNDETERMINED}
 *   <li>{@code sufficiency.action} == REPLAN
 *   <li>{@code missingRequirementIds} 非空
 *   <li>{@code replanCount} == 0 (PR-7c.2 硬上限)
 *   <li>Run 仍 EXECUTING (PhaseExecutionResult.prematureTerminal == null)
 *   <li>无 PERMISSION_DENIED
 *   <li>无 TIMEOUT
 *   <li>无 CANCELLATION
 *   <li>Budget 足够 (PhaseExecutionResult.reservation 至少有 1 step + 1 toolCall 余额; 或 inferred
 *       remaining budget > 0)
 *   <li>required Tool 未 terminal failure (requiredStepFailed=false)
 *   <li>无 Conflict (sufficiency.conflicts.isEmpty())
 *   <li>Phase 真有进展 (AgentProgressDetector = PROGRESS)
 * </ol>
 *
 * <p>禁止 Replan 的情况 (Revision §10.2 表给出对应终态):
 *
 * <ul>
 *   <li>PERMISSION_DENIED → REFUSED_PERMISSION
 *   <li>required Tool terminal failure → TOOL_FAILED
 *   <li>Budget denied → BUDGET_EXCEEDED
 *   <li>Deadline → TIMED_OUT
 *   <li>Cancellation → CANCELLED
 *   <li>Conflict → REFUSED_CONFLICT
 *   <li>Initial Plan invalid (Phase 0 之前已 SYSTEM_FAILED) → SYSTEM_FAILED
 *   <li>No progress → REFUSED_NO_EVIDENCE (reasonCode=AGENT_NO_PROGRESS)
 *   <li>replanCount >= 1 → REFUSED_NO_EVIDENCE (reasonCode=REPLAN_EXHAUSTED)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplanDecisionCoordinator {

    private final AgentProgressDetector progressDetector;

    public ReplanDecision decide(
            PhaseExecutionResult phaseResult,
            SufficiencyDecision sufficiency,
            Set<String> priorAccumulatedEvidenceIds,
            EvidenceCoverageSummary priorCoverage,
            int replanCount,
            int maxReplans,
            boolean cancellationRequested,
            AgentBudget remainingBudget) {
        if (phaseResult == null) throw new IllegalArgumentException("phaseResult");
        if (sufficiency == null) throw new IllegalArgumentException("sufficiency");

        // 1. Phase 内已 premature terminal → 直接转相应终态, 不 Replan
        if (phaseResult.prematureTerminal() != null) {
            return ReplanDecision.refuse(
                    toRefuseTerminal(phaseResult.prematureTerminal()),
                    phaseResult.failureReasonCode());
        }

        // 2. 用户取消 → CANCELLED
        if (cancellationRequested) {
            return ReplanDecision.refuse(AgentRunStatus.CANCELLED, "USER_CANCELLED");
        }

        // 3. Sufficiency 已 SUFFICIENT 或 CONFLICTED → 不需也不应 Replan
        if (sufficiency.status() == SufficiencyStatus.SUFFICIENT) {
            return ReplanDecision.noReplanNeeded();
        }
        if (sufficiency.status() == SufficiencyStatus.CONFLICTED
                || !sufficiency.conflicts().isEmpty()) {
            return ReplanDecision.refuse(
                    AgentRunStatus.REFUSED_CONFLICT, "REPLAN_BLOCKED_BY_CONFLICT");
        }

        // 4. 必要前置状态: INSUFFICIENT / UNDETERMINED + missingRequirementIds 非空
        if (sufficiency.missingRequirementIds().isEmpty()) {
            return ReplanDecision.refuse(
                    AgentRunStatus.REFUSED_NO_EVIDENCE, "NO_MISSING_REQUIREMENT");
        }

        // 5. Replan 次数已用完
        if (replanCount >= Math.max(0, maxReplans)) {
            return ReplanDecision.refuse(AgentRunStatus.REFUSED_NO_EVIDENCE, "REPLAN_EXHAUSTED");
        }

        // 6. 进展检测 — 无进展直接 REFUSED_NO_EVIDENCE
        AgentProgressDetector.Outcome progress =
                progressDetector.detect(
                        priorAccumulatedEvidenceIds,
                        phaseResult.newEvidence(),
                        phaseResult.discoveredEntities(),
                        priorCoverage == null
                                ? java.util.List.of()
                                : priorCoverage.uncoveredRequirementIds(),
                        sufficiency.missingRequirementIds());
        if (progress == AgentProgressDetector.Outcome.NO_PROGRESS) {
            return ReplanDecision.refuse(AgentRunStatus.REFUSED_NO_EVIDENCE, "AGENT_NO_PROGRESS");
        }

        // 7. Budget 检查 — 至少留 1 step + 1 toolCall 余额
        if (remainingBudget != null) {
            if (remainingBudget.maxSteps() - phaseResult.usage().usedSteps() <= 0
                    || remainingBudget.maxToolCalls() - phaseResult.usage().usedToolCalls() <= 0) {
                return ReplanDecision.refuse(
                        AgentRunStatus.BUDGET_EXCEEDED, "REPLAN_BUDGET_INSUFFICIENT");
            }
        }

        // 全部通过 — 允许 Replan
        log.info(
                "replan.allowed run={} replanCount={} missing={}",
                phaseResult.runId(),
                replanCount,
                sufficiency.missingRequirementIds());
        return ReplanDecision.allow(phaseResult, sufficiency);
    }

    /** 把 Phase premature AgentRunStatus 映射为 Replan refuseReason 同义终态。 */
    private static AgentRunStatus toRefuseTerminal(AgentRunStatus prematurely) {
        return prematurely; // PERMISSION_DENIED → REFUSED_PERMISSION 由 Phase 直接给的精确终态
    }

    /** Replan 决策结果。 */
    public record ReplanDecision(
            boolean allowed,
            String reasonIfRefused,
            AgentRunStatus terminalStatusIfRefused,
            PhaseExecutionResult phaseResult,
            SufficiencyDecision sufficiency) {

        static ReplanDecision allow(PhaseExecutionResult r, SufficiencyDecision s) {
            return new ReplanDecision(true, null, null, r, s);
        }

        static ReplanDecision noReplanNeeded() {
            return new ReplanDecision(
                    false,
                    "SUFFICIENCY_ALREADY_SUFFICIENT",
                    AgentRunStatus.READY_TO_ANSWER,
                    null,
                    null);
        }

        static ReplanDecision refuse(AgentRunStatus terminal, String reason) {
            return new ReplanDecision(false, reason, terminal, null, null);
        }
    }
}
