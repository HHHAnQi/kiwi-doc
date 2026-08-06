package com.xxx.ragdoc.application.chat.sufficiency;

import java.util.List;

/**
 * PR-7b / EMS-PR7 §6.1: Judge 输出。
 *
 * <p>Builder-like record; Rule Judge / Model Judge 都返回此类型。Pipeline 据 {@link #action} 路由。
 *
 * @param status 整体覆盖状态
 * @param coverage 每个 Requirement 的 Coverage (按 requirements 顺序)
 * @param missingRequirementIds NOT_COVERED / PARTIALLY_COVERED 的 required req ids (Replan 输入)
 * @param conflicts CONFLICTED 时至少 1 条; 否则空 list
 * @param action Pipeline 路由建议 (ANSWER / REPLAN / REFUSE_NO_EVIDENCE / REFUSE_CONFLICT)
 * @param reasonCode 短枚举 (Rule Sufficiency Sufficient / Rule Conflict / Model Verdict 等)
 * @param source "RULE" / "MODEL" — Trace + 反消费 False Sufficient 监控
 */
public record SufficiencyDecision(
        SufficiencyStatus status,
        List<RequirementCoverage> coverage,
        List<String> missingRequirementIds,
        List<EvidenceConflict> conflicts,
        RecommendedAction action,
        String reasonCode,
        String source) {

    public SufficiencyDecision {
        if (status == null) throw new IllegalArgumentException("status 必填");
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        missingRequirementIds =
                missingRequirementIds == null ? List.of() : List.copyOf(missingRequirementIds);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        if (action == null) action = RecommendedAction.REFUSE_NO_EVIDENCE;
        if (reasonCode == null) reasonCode = "";
        if (source == null) source = "RULE";
        // 不变量: CONFLICTED 必须有 conflicts; CONFLICTED 不要遗漏 -> CONFLICTED+空 conflicts 视为 SYSTEM_FAILED bug
        if (status == SufficiencyStatus.CONFLICTED && conflicts.isEmpty()) {
            throw new IllegalArgumentException("CONFLICTED 必须含 ≥1 EvidenceConflict");
        }
        // CONFLICTED 时 action 必为 REFUSE_CONFLICT
        if (status == SufficiencyStatus.CONFLICTED && action != RecommendedAction.REFUSE_CONFLICT) {
            throw new IllegalArgumentException("CONFLICTED 必须 action=REFUSE_CONFLICT");
        }
    }

    /** Rule Judge 快速构造。 */
    public static SufficiencyDecision rule(
            SufficiencyStatus status,
            List<RequirementCoverage> coverage,
            List<String> missing,
            List<EvidenceConflict> conflicts,
            RecommendedAction action,
            String reason) {
        String shortReason = reason == null ? "" : reason;
        return new SufficiencyDecision(status, coverage, missing, conflicts, action, shortReason, "RULE");
    }

    public static SufficiencyDecision model(
            SufficiencyStatus status,
            List<RequirementCoverage> coverage,
            List<String> missing,
            List<EvidenceConflict> conflicts,
            RecommendedAction action,
            String reason) {
        String shortReason = reason == null ? "" : reason;
        return new SufficiencyDecision(status, coverage, missing, conflicts, action, shortReason, "MODEL");
    }

    /** False Sufficient 防护: rule/model source 反查 (评测 / Trace)。 */
    public boolean isFalsifiableFlag() {
        return status == SufficiencyStatus.SUFFICIENT && "MODEL".equals(source);
    }
}
