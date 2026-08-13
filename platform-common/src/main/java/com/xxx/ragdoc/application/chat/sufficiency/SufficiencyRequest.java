package com.xxx.ragdoc.application.chat.sufficiency;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR-7b / EMS-PR7 §6.1: {@link EvidenceSufficiencyJudge} 输入。
 *
 * <p>不含 LLM prompt 编辑字段; 全部由服务端构造, Model Judge 内部按需提取 Prompt 字段。
 *
 * <p>硬约束 (Revision §5.2 数据最小化 + §6 Sufficiency 边界):
 *
 * <ul>
 *   <li><b>不</b>含 tenantId/Principal/Token/Cookie
 *   <li><b>不</b>含 internal exception / Trace / Agent Transcript
 *   <li>Evidence 列表只能用于证据覆盖判定, 不能让 Judge 发起新检索 / 修改 Plan
 *   <li>{@code allowModelFallback=false} 时 Rule 无法判定 → 必须 UNDETERMINED 保守拒答
 * </ul>
 */
public record SufficiencyRequest(
        String runId,
        String normalizedQuery,
        List<EvidenceRequirement> requirements,
        List<Evidence> evidence,
        /** completedRequiredStepIds: 已 SUCCEEDED 的 required Step IDs — 全部未完成则 INSUFFICIENT。 */
        Set<String> completedRequiredStepIds,
        /** 已使用 Tool signatures (loop detect 复用, 防 Replan 重复 signature)。 */
        Set<String> usedToolSignatures,
        EvidenceCoverageSummary precomputedCoverage /* 可空; Judge 可选用 */,
        int replanIndex,
        boolean allowModelFallback,
        /** 安全元数据 (version fingerprint 等); 不含 sensitive raw。 */
        Map<String, Object> safeMeta) {

    public SufficiencyRequest {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (normalizedQuery == null) normalizedQuery = "";
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        completedRequiredStepIds =
                completedRequiredStepIds == null ? Set.of() : Set.copyOf(completedRequiredStepIds);
        usedToolSignatures = usedToolSignatures == null ? Set.of() : Set.copyOf(usedToolSignatures);
        if (precomputedCoverage == null) {
            precomputedCoverage =
                    com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary.empty();
        }
        if (replanIndex < 0) replanIndex = 0;
        safeMeta = safeMeta == null ? Map.of() : Map.copyOf(safeMeta);
    }
}
