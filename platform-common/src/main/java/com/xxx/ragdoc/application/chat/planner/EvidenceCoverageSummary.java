package com.xxx.ragdoc.application.chat.planner;

import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2: 当前 Evidence 覆盖摘要, 作为 Planner Replan 输入与 Sufficiency 内部 已知结果共享的轻量结构。
 *
 * <p>不含 Evidence 正文; 只持 count + 已覆盖 requirement IDs + Evidence IDs 列表 (供 Planner 决定 下一步是否 retry 既定
 * requirement / expand 到 follow-up entity)。
 */
public record EvidenceCoverageSummary(
        int totalEvidence,
        java.util.List<String> coveredRequirementIds,
        java.util.List<String> partialRequirementIds,
        java.util.List<String> uncoveredRequirementIds,
        java.util.List<String> evidenceIds /* 全部累积 Evidence IDs, 用作 trace anchor */,
        Map<String, Object> safeStats /* 例如 {"leftCount": 2, "rightCount": 3} 可选 */) {

    public EvidenceCoverageSummary {
        coveredRequirementIds =
                coveredRequirementIds == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(coveredRequirementIds);
        partialRequirementIds =
                partialRequirementIds == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(partialRequirementIds);
        uncoveredRequirementIds =
                uncoveredRequirementIds == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(uncoveredRequirementIds);
        evidenceIds =
                evidenceIds == null ? java.util.List.of() : java.util.List.copyOf(evidenceIds);
        safeStats = safeStats == null ? Map.of() : Map.copyOf(safeStats);
    }

    public static EvidenceCoverageSummary empty() {
        return new EvidenceCoverageSummary(
                0,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                Map.of());
    }
}
