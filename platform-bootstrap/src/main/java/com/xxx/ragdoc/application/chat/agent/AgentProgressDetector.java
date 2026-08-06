package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * PR-7c.2 / EMS-PR7 §11: 单 Phase 进展判定器。
 *
 * <p>无进展定义 (Revision §11):
 *
 * <pre>
 *   newEvidenceIds 空
 *   且 newContentHashes 空
 *   且 newDiscoveredEntities 空
 *   且 missingRequirementIds 未减少
 * </pre>
 *
 * <p>结果 NO_PROGRESS → 不调 Replan Planner, 直接 REFUSED_NO_EVIDENCE,
 * reasonCode = AGENT_NO_PROGRESS。
 *
 * <p>不能仅以 Tool 返回 SUCCESS 判定有进展 (Revision §11 末段; CACHE 命中
 * 也算成功但不算新进展)。
 */
@Component
public class AgentProgressDetector {

    /** 阶段进展结果。 */
    public enum Outcome {
        PROGRESS,
        NO_PROGRESS
    }

    /**
     * 比较 Phase 之前 / 之后的指标。
     *
     * @param priorAccumulatedEvidenceIds  累积到 prior 的 evidence ids
     * @param phaseNewEvidence             本 Phase 新增 Evidence
     * @param phaseDiscoveredEntities      本 Phase 新发现的 entities
     * @param priorUncoveredRequirementIds Phase 之前未覆盖的 required req ids
     * @param currentUncoveredRequirementIds Phase 之后未覆盖的 required req ids
     */
    public Outcome detect(
            Set<String> priorAccumulatedEvidenceIds,
            java.util.List<Evidence> phaseNewEvidence,
            Set<String> phaseDiscoveredEntities,
            java.util.List<String> priorUncoveredRequirementIds,
            java.util.List<String> currentUncoveredRequirementIds) {
        Set<String> priorReq = normalize(priorUncoveredRequirementIds);
        Set<String> currentReq = normalize(currentUncoveredRequirementIds);

        // 1. 新 Evidence id 出现 → PROGRESS (权威信号)
        for (Evidence e : phaseNewEvidence) {
            if (priorAccumulatedEvidenceIds == null
                    || !priorAccumulatedEvidenceIds.contains(e.evidenceId())) {
                return Outcome.PROGRESS;
            }
        }

        // 2. 新 entities → PROGRESS
        if (phaseDiscoveredEntities != null && !phaseDiscoveredEntities.isEmpty()) {
            return Outcome.PROGRESS;
        }

        // 3. missingRequirementIds 减少 → PROGRESS
        if (currentReq.size() < priorReq.size()) {
            return Outcome.PROGRESS;
        }

        return Outcome.NO_PROGRESS;
    }

    private static Set<String> normalize(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return new HashSet<>();
        return new HashSet<>(list);
    }
}
