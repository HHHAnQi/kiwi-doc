package com.xxx.ragdoc.application.chat.sufficiency;

import java.util.List;

/**
 * PR-7b / EMS-PR7 §6.3: 单 Requirement 的覆盖详情。
 *
 * <p>由 Rule Judge / Model Judge 输出; Pipeline 与评测都引用此结构。
 *
 * @param requirementId 关联 {@link com.xxx.ragdoc.application.chat.planner.EvidenceRequirement#requirementId()}
 * @param status 覆盖状态 (不允许 null)
 * @param evidenceIds 命中的 Evidence IDs (空 = 未覆盖)
 * @param reasonCode 短代码便于 Trace / Metrics
 */
public record RequirementCoverage(
        String requirementId,
        CoverageStatus status,
        List<String> evidenceIds,
        String reasonCode) {

    public RequirementCoverage {
        if (requirementId == null || requirementId.isBlank()) {
            throw new IllegalArgumentException("RequirementCoverage.requirementId 必填");
        }
        if (status == null) throw new IllegalArgumentException("status 必填");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (reasonCode == null) reasonCode = "";
        // 不变量: COVERED 必须有 ≥1 Evidence ID (False Sufficient 防护, Revision §6.6)
        if (status == CoverageStatus.COVERED && evidenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "RequirementCoverage COVERED 但 evidenceIds 为空 (False Sufficient 风险): "
                            + requirementId);
        }
    }

    public static RequirementCoverage covered(String rid, List<String> evIds, String reason) {
        return new RequirementCoverage(rid, CoverageStatus.COVERED, evIds, reason);
    }

    public static RequirementCoverage notCovered(String rid, String reason) {
        return new RequirementCoverage(rid, CoverageStatus.NOT_COVERED, List.of(), reason);
    }

    public static RequirementCoverage conflicted(String rid, List<String> evIds, String reason) {
        return new RequirementCoverage(rid, CoverageStatus.CONFLICTED, evIds, reason);
    }
}
