package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.sufficiency.CoverageStatus;
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * PR-7c.3a / EMS-PR7 §3: 回答前<b>第三层</b> Sufficiency 硬门禁。
 *
 * <p>RequirementCoverage ctor 已经守住"COVERED 必含 ≥1 evidenceId"; ModelSufficiencyJudge 已经守 "模型虚假
 * COVERED 降 NOT_COVERED"; 但 Pipeline 进入 Answer Composer 前<b>不可</b>信任单一 Judge 决策, 必须本 Guard 再校验全部维度
 * (False Sufficient 防护, Revision §6.6 + §8.3)。
 *
 * <p>允许回答必须同时满足:
 *
 * <ol>
 *   <li>status == SUFFICIENT
 *   <li>action == ANSWER
 *   <li>!isFalsifiableFlag (即非 Model 直接判 Sufficient 但可以用, PR-7c.3a 选择保守 flag=false 视通过; Guard 独立再检查
 *       coverage, 不依赖 flag 本身)
 *   <li>missingRequirementIds 为空
 *   <li>conflicts 为空
 *   <li>required Requirement 全部存在 coverage, 且 coverage.status == COVERED
 *   <li>每个 COVERED coverage 至少 1 个 evidenceId
 *   <li>所有 coverage.evidenceId 必须在授权 evidence 列表中
 *   <li>coverage.requirementId 必须存在于 requirements 中
 *   <li>不能有同一 requirement 重复 coverage (fuse/duplicate)
 * </ol>
 *
 * <p>任一不满足 → REJECTED, 不允许 Answer, Pipeline 应转 REFUSED_NO_EVIDENCE reasonCode =
 * FALSE_SUFFICIENT_GUARD_REJECTED。
 */
@Component
public class SufficiencyDecisionGuard {

    /** 输出。REJECTED 必含 reasonCode。 */
    public record GuardResult(boolean allowed, String reasonCode) {
        public static GuardResult allow() {
            return new GuardResult(true, "");
        }

        public static GuardResult reject(String reason) {
            return new GuardResult(
                    false, reason == null ? "FALSE_SUFFICIENT_GUARD_REJECTED" : reason);
        }
    }

    public GuardResult validateForAnswer(
            SufficiencyDecision decision,
            List<EvidenceRequirement> requirements,
            List<Evidence> evidence) {
        if (decision == null) return GuardResult.reject("NULL_DECISION");
        if (decision.status() != SufficiencyStatus.SUFFICIENT) {
            return GuardResult.reject("STATUS_NOT_SUFFICIENT:" + decision.status());
        }
        if (decision.action() != RecommendedAction.ANSWER) {
            return GuardResult.reject("ACTION_NOT_ANSWER:" + decision.action());
        }
        if (!decision.missingRequirementIds().isEmpty()) {
            return GuardResult.reject("MISSING_NOT_EMPTY:" + decision.missingRequirementIds());
        }
        if (!decision.conflicts().isEmpty()) {
            return GuardResult.reject("CONFLICTS_NOT_EMPTY");
        }

        // evidence id set
        Set<String> evidenceIds = new HashSet<>();
        if (evidence != null) {
            for (Evidence e : evidence) evidenceIds.add(e.evidenceId());
        }

        // required req IDs
        Set<String> requiredReqIds = new HashSet<>();
        Set<String> knownReqIds = new HashSet<>();
        if (requirements != null) {
            for (EvidenceRequirement r : requirements) {
                knownReqIds.add(r.requirementId());
                if (r.required()) requiredReqIds.add(r.requirementId());
            }
        }

        // coverage by req id, dedup check
        Set<String> coveredReqIds = new HashSet<>();
        for (RequirementCoverage cov : decision.coverage()) {
            if (cov.requirementId() == null || !knownReqIds.contains(cov.requirementId())) {
                return GuardResult.reject("COVERAGE_UNKNOWN_REQ:" + cov.requirementId());
            }
            if (!coveredReqIds.add(cov.requirementId())) {
                return GuardResult.reject("DUPLICATE_COVERAGE:" + cov.requirementId());
            }
            if (cov.status() == CoverageStatus.COVERED) {
                if (cov.evidenceIds().isEmpty()) {
                    return GuardResult.reject("COVERED_NO_EVIDENCE:" + cov.requirementId());
                }
                for (String eid : cov.evidenceIds()) {
                    if (!evidenceIds.contains(eid)) {
                        return GuardResult.reject(
                                "EVIDENCE_NOT_AUTHORIZED:" + cov.requirementId() + "/" + eid);
                    }
                }
            }
        }

        // 每个 required Requirement 必须有 COVERED coverage
        for (String reqId : requiredReqIds) {
            RequirementCoverage cov =
                    decision.coverage().stream()
                            .filter(c -> reqId.equals(c.requirementId()))
                            .findFirst()
                            .orElse(null);
            if (cov == null) {
                return GuardResult.reject("REQUIRED_HAS_NO_COVERAGE:" + reqId);
            }
            if (cov.status() != CoverageStatus.COVERED) {
                return GuardResult.reject("REQUIRED_NOT_COVERED:" + reqId + "/" + cov.status());
            }
        }

        return GuardResult.allow();
    }
}
