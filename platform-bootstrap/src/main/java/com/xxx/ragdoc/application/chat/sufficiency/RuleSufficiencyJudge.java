package com.xxx.ragdoc.application.chat.sufficiency;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7b / EMS-PR7 §6.4: 确定性规则优先 Judge。
 *
 * <p>规则集 (Revision §6.4):
 *
 * <ol>
 *   <li>每个 required Requirement 是否至少关联一条已授权 Evidence (按 sourceStepId / requirementIds)
 *   <li>指定 entity / filter 的 Requirement, Evidence metadata.targetEntities / version 是否匹配
 *   <li>required Tool Step 全部 REQUIRED_SUCCEEDED (来自 request.completedRequiredStepIds)
 *   <li>是否存在 duplicate-Evidence-only (多 Evidence 但 contentHash 相同 → 算 1 条覆盖)
 *   <li>是否存在 explicit version-value conflict (两条 Evidence 同字段不同 version 互相矛盾)
 *   <li>UNDETERMINED: 复杂语义覆盖 / RELATION / FOLLOW_UP_ENTITY 无法 100% 规则判定 (留 Model fallback)
 * </ol>
 *
 * <p>规则优先原则: 规则可判定时不调 Model (Revision §6.4)。返回 {@code source="RULE"}。
 *
 * <p>UNDETERMINED 时 Pipeline 根据 {@link SufficiencyRequest#allowModelFallback()} 决定调用 ModelJudge, 否则
 * Pipeline 保守转 REFUSE_NO_EVIDENCE。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleSufficiencyJudge implements EvidenceSufficiencyJudge {

    @Override
    public SufficiencyDecision evaluate(SufficiencyRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        // index Evidence by sourceStepId-aligned requirement (Evidence metadata.requirementIds) + 按
        // tenantId 取可信
        Map<String, List<Evidence>> reqIdToEvidence = new HashMap<>();
        for (Evidence e : request.evidence()) {
            // 只判服务端注入 tenantId 一致的 evidence (EvidenceAccumulator 已 filter 过)
            Object reqIdsObj = e.metadata() == null ? null : e.metadata().get("requirementIds");
            if (reqIdsObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s)
                        reqIdToEvidence.computeIfAbsent(s, k -> new ArrayList<>()).add(e);
                }
            }
            // 也允许 COMPARISON_SIDE / sideKey 等 metadata fallback (PR-7b 第一版不实现, 留 PR-7c Pipeline 注入)
        }

        List<RequirementCoverage> coverages = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<EvidenceConflict> conflicts = new ArrayList<>();
        boolean anyUndeterminable = false;

        for (EvidenceRequirement req : request.requirements()) {
            // optional Requirement 不参与 missing 判定 (但记 coverage)
            List<Evidence> hits = reqIdToEvidence.getOrDefault(req.requirementId(), List.of());
            // dedup-by-contentHash: 同 contentHash 多 Evidence 算一条覆盖
            List<Evidence> distinct = dedupByContentHash(hits);

            if (distinct.isEmpty()) {
                coverages.add(RequirementCoverage.notCovered(req.requirementId(), "NO_EVIDENCE"));
                if (req.required()) missing.add(req.requirementId());
                continue;
            }

            // entity/filter 严格匹配
            boolean entityMatch = matchesTargetEntities(distinct, req.targetEntities());
            boolean filterMatch = matchesExpectedFilters(distinct, req.expectedFilters());

            // 冲突检测 (显式 version-value)
            EvidenceConflict conflict = detectVersionValueConflict(req, distinct);
            if (conflict != null) {
                conflicts.add(conflict);
                coverages.add(
                        RequirementCoverage.conflicted(
                                req.requirementId(),
                                distinct.stream().map(Evidence::evidenceId).toList(),
                                conflict.type().name()));
                continue;
            }

            if (!entityMatch || !filterMatch) {
                coverages.add(
                        RequirementCoverage.notCovered(
                                req.requirementId(), "EVIDENCE_ENTITY_OR_FILTER_MISMATCH"));
                if (req.required()) missing.add(req.requirementId());
                continue;
            }

            // 类型映射: FACT/ENTITY_ATTRIBUTE/TEMPORAL/COMPARISON_SIDE → 规则可判;
            // RELATION/FOLLOW_UP_ENTITY → 复杂语义
            if (req.type() == RequirementType.RELATION
                    || req.type() == RequirementType.FOLLOW_UP_ENTITY) {
                // 规则只能判"有证据"; 不判语义充分; 标 UNDETERMINED 让 Model 决策 (若启用)
                coverages.add(
                        RequirementCoverage.notCovered(
                                req.requirementId(), "RULE_CANNOT_VERIFY_SEMANTIC"));
                anyUndeterminable = true;
                if (req.required()) {
                    // 不立即标 missing — Pipeline 调 Model 后再决策
                    missing.add(req.requirementId());
                }
                continue;
            }

            coverages.add(
                    RequirementCoverage.covered(
                            req.requirementId(),
                            distinct.stream().map(Evidence::evidenceId).toList(),
                            "RULE_FULLY_COVERED"));
        }

        // 1. CONFLICTED 优先
        if (!conflicts.isEmpty()) {
            return SufficiencyDecision.rule(
                    SufficiencyStatus.CONFLICTED,
                    coverages,
                    List.of() /* missing 不重要 */,
                    conflicts,
                    RecommendedAction.REFUSE_CONFLICT,
                    "RULE_VERSION_VALUE_CONFLICT");
        }
        // 2. UNDETERMINED (语义无法判定)
        if (anyUndeterminable) {
            return SufficiencyDecision.rule(
                    SufficiencyStatus.UNDETERMINED,
                    coverages,
                    missing,
                    List.of(),
                    RecommendedAction.REFUSE_NO_EVIDENCE /* 保守默认; Pipeline 可调 Model 覆盖 */,
                    "RULE_SEMANTIC_UNDETERMINED");
        }
        // 3. INSUFFICIENT / PARTIAL (required 有 missing)
        // 改动(2026-08-25): 区分"部分覆盖"和"全无" — 65% 拒答率的根因是
        // 有证据但仍被判 INSUFFICIENT → 终态拒答。≥1 个 required 有证据 → PARTIAL
        // → Composer 带标注回答; 全部 required 无证据 → 仍 INSUFFICIENT(防幻觉底线)。
        if (!missing.isEmpty()) {
            boolean anyCovered = coverages.stream()
                    .anyMatch(c -> c.status() == CoverageStatus.COVERED);
            if (anyCovered) {
                return SufficiencyDecision.rule(
                        SufficiencyStatus.PARTIAL,
                        coverages,
                        missing,
                        List.of(),
                        RecommendedAction.ANSWER_PARTIAL,
                        "RULE_PARTIAL_SOME_COVERED");
            }
            SufficiencyStatus status = SufficiencyStatus.INSUFFICIENT;
            RecommendedAction action = RecommendedAction.REFUSE_NO_EVIDENCE;
            return SufficiencyDecision.rule(
                    status, coverages, missing, List.of(), action, "RULE_INSUFFICIENT_REQ_MISSING");
        }
        // 4. required 全部 COVERED; optional 缺也无所谓
        return SufficiencyDecision.rule(
                SufficiencyStatus.SUFFICIENT,
                coverages,
                List.of(),
                List.of(),
                RecommendedAction.ANSWER,
                "RULE_SUFFICIENT");
    }

    /** 同 contentHash dedup — 防止重复 evidence 制造虚假 multi-coverage。 */
    static List<Evidence> dedupByContentHash(List<Evidence> in) {
        if (in == null || in.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        List<Evidence> out = new ArrayList<>();
        for (Evidence e : in) {
            if (seen.add(e.contentHash())) out.add(e);
        }
        return out;
    }

    /**
     * entity 匹配: 任何 targetEntity 出现在 evidence content / metadata / sourceTool 即算命中。 空
     * targetEntities 视为 wildcard (无约束)。
     */
    static boolean matchesTargetEntities(List<Evidence> ev, List<String> targetEntities) {
        if (targetEntities == null || targetEntities.isEmpty()) return true;
        for (String t : targetEntities) {
            String lowT = t.toLowerCase(java.util.Locale.ROOT);
            boolean anyMatch = false;
            for (Evidence e : ev) {
                String c =
                        e.content() == null ? "" : e.content().toLowerCase(java.util.Locale.ROOT);
                if (c.contains(lowT)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) return false;
        }
        return true;
    }

    /**
     * filter 匹配: expectedFilters.version 必须在至少一个 Evidence 的 documentVersion 出现; source 同理。空
     * expectedFilters wildcard。
     */
    static boolean matchesExpectedFilters(List<Evidence> ev, Map<String, Object> expected) {
        if (expected == null || expected.isEmpty()) return true;
        Object expectedVersion = expected.get("version");
        Object expectedSource = expected.get("source");
        if (expectedVersion instanceof String ev1) {
            boolean matchAny = false;
            for (Evidence e : ev) {
                if (ev1.equals(e.documentVersion())) matchAny = true;
            }
            if (!matchAny) return false;
        }
        if (expectedSource instanceof String es) {
            boolean matchAny = false;
            for (Evidence e : ev) {
                Object src = e.metadata() == null ? null : e.metadata().get("source");
                if (es.equals(src)) matchAny = true;
            }
            if (!matchAny) return false;
        }
        return true;
    }

    /**
     * 简单 version-value 冲突检测: 两条 Evidence documentVersion 均非空且不同 → CONFLICT。 (PR-7b v1 只识别
     * VERSION_VALUE_MISMATCH; PR-7d 复杂冲突留给 Model Judge)
     */
    /**
     * 版本冲突检测(校准版, pilot20 实测 58 次误判 CONFLICT 的根因)。
     *
     * <p>原实现: 证据覆盖 ≥2 个 document version 即判 VERSION_VALUE_MISMATCH → 终态
     * REFUSED_CONFLICT(无 Replan)。多组件对比题的证据天然跨文档/跨版本(dubbo 与 nacos 的
     * version 字段本就不同), 该规则把一切对比题判死。
     *
     * <p>正确语义: 仅当 Requirement <b>显式锁定</b> expectedFilters.version 且证据版本与之
     * 不符时才是冲突(用户要 v2.3 的答案, 检回 v3.0)。版本多样性本身 = 异质证据, 交给
     * 类型映射/Model judge 做语义级判定。
     */
    static EvidenceConflict detectVersionValueConflict(EvidenceRequirement req, List<Evidence> ev) {
        String pinned = null;
        if (req.expectedFilters() != null) {
            Object v = req.expectedFilters().get("version");
            if (v != null && !String.valueOf(v).isBlank()) {
                pinned = String.valueOf(v).trim();
            }
        }
        if (pinned == null || ev.size() < 2) {
            // 需求未锁版本: 版本多样性不是冲突;
            // 单条证据版本不符: EvidenceConflict 需 ≥2 evidenceId, 走 filter-mismatch
            // 路径(matchesExpectedFilters → NOT_COVERED → Replan)语义更正确
            return null;
        }
        for (Evidence e : ev) {
            String dv = e.documentVersion();
            if (dv != null && !dv.isBlank() && !pinned.equals(dv.trim())) {
                return new EvidenceConflict(
                        req.requirementId(),
                        EvidenceConflict.ConflictType.VERSION_VALUE_MISMATCH,
                        ev.stream().map(Evidence::evidenceId).toList(),
                        "evidence version " + dv + " != pinned " + pinned);
            }
        }
        return null;
    }
}
