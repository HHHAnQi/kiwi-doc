package com.xxx.ragdoc.application.chat.sufficiency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PR-7b: {@link RuleSufficiencyJudge} 关键契约单测。 */
@DisplayName("RuleSufficiencyJudge - PR-7b 规则优先 + False Sufficient 防护")
class RuleSufficiencyJudgeTest {

    private RuleSufficiencyJudge judge;

    @BeforeEach
    void setup() {
        judge = new RuleSufficiencyJudge();
    }

    private Evidence ev(
            String tenant, String reqId, String content, String version, String source) {
        Map<String, Object> md = new java.util.HashMap<>();
        if (reqId != null) md.put("requirementIds", List.of(reqId));
        if (source != null) md.put("source", source);
        return Evidence.of(tenant, 1L, 10L, version, content, 0.9, null, "metadata_search", md);
    }

    private EvidenceRequirement req(
            String id,
            RequirementType type,
            boolean required,
            List<String> entities,
            Map<String, Object> filters) {
        return new EvidenceRequirement(id, "desc-" + id, type, required, entities, filters);
    }

    private SufficiencyRequest request(List<EvidenceRequirement> reqs, List<Evidence> evs) {
        return new SufficiencyRequest(
                "r1",
                "q",
                reqs,
                evs,
                Set.of(),
                Set.of(),
                EvidenceCoverageSummary.empty(),
                0,
                false,
                Map.of());
    }

    @Nested
    @DisplayName("常态覆盖")
    class HappyCoverage {

        @Test
        @DisplayName("完整 required 覆盖 → SUFFICIENT + ANSWER")
        void allCovered() {
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of(), Map.of());
            Evidence ev = ev("tA", "R1", "v2 content", "v2", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(ev)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
            assertThat(d.action()).isEqualTo(RecommendedAction.ANSWER);
            assertThat(d.coverage()).hasSize(1);
            assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.COVERED);
        }

        @Test
        @DisplayName("缺一个 required → INSUFFICIENT + REFUSE_NO_EVIDENCE")
        void requiredMissing() {
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of(), Map.of());
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of()));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
            assertThat(d.action()).isEqualTo(RecommendedAction.REFUSE_NO_EVIDENCE);
            assertThat(d.missingRequirementIds()).containsExactly("R1");
        }

        @Test
        @DisplayName("optional 缺失不进入 missing → SUFFICIENT")
        void optionalMissingNotBlocking() {
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of(), Map.of());
            EvidenceRequirement r2 = req("R2", RequirementType.FACT, false, List.of(), Map.of());
            Evidence ev = ev("tA", "R1", "ok", "v2", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1, r2), List.of(ev)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        }

        @Test
        @DisplayName("Evidence entity 不匹配 → NOT_COVERED + INSUFFICIENT")
        void entityMismatch() {
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of("v2"), Map.of());
            Evidence ev = ev("tA", "R1", "完全无关的内容", "v2", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(ev)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
            assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.NOT_COVERED);
        }

        @Test
        @DisplayName("Evidence version 不匹配 expectedFilters.version → NOT_COVERED")
        void versionMismatch() {
            EvidenceRequirement r1 =
                    req("R1", RequirementType.FACT, true, List.of(), Map.of("version", "v2"));
            Evidence ev = ev("tA", "R1", "v2 some", "v1", null); // evidence 是 v1 但期望 v2
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(ev)));
            assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.NOT_COVERED);
            assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
        }
    }

    @Nested
    @DisplayName("去重 + 冲突")
    class DupAndConflict {

        @Test
        @DisplayName("同 contentHash 多 Evidence → dedup 算 1 条 (防虚假 multi-coverage)")
        void contentHashDedup() {
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of(), Map.of());
            Evidence a = ev("tA", "R1", "same", "v2", null);
            Evidence b = ev("tA", "R1", "same", "v2", null); // 同 contentHash
            List<Evidence> dedup = RuleSufficiencyJudge.dedupByContentHash(List.of(a, b));
            assertThat(dedup).hasSize(1);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(a, b)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        }

        @Test
        @DisplayName("校准: 未锁版本的版本多样性 = 异质证据非冲突(对比题不误杀) → SUFFICIENT")
        void versionDiversityIsNotConflict() {
            // pilot20 实测根因: 多组件对比题证据天然跨文档版本, 旧规则(≥2 version 即
            // CONFLICT 终态)把一切对比题判死 → 58/67 REFUSED_CONFLICT
            EvidenceRequirement r1 = req("R1", RequirementType.FACT, true, List.of(), Map.of());
            Evidence a = ev("tA", "R1", "v2 fact", "v2", null);
            Evidence b = ev("tA", "R1", "v1 fact", "v1", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(a, b)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        }

        @Test
        @DisplayName("校准: 需求锁定版本且证据不符(≥2条) → 仍 CONFLICTED")
        void pinnedVersionMismatchStillConflicts() {
            EvidenceRequirement r1 =
                    req("R1", RequirementType.FACT, true, List.of(), Map.of("version", "v2"));
            Evidence a = ev("tA", "R1", "v2 fact", "v2", null);
            Evidence b = ev("tA", "R1", "v1 fact", "v1", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(a, b)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.CONFLICTED);
            assertThat(d.action()).isEqualTo(RecommendedAction.REFUSE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("语义 UNDETERMINED")
    class Undeterminable {

        @Test
        @DisplayName("RELATION 类型 Rule 无法判 → UNDETERMINED")
        void relationUndetermined() {
            EvidenceRequirement r1 = req("R1", RequirementType.RELATION, true, List.of(), Map.of());
            Evidence ev = ev("tA", "R1", "maybe related", "v2", null);
            SufficiencyDecision d = judge.evaluate(request(List.of(r1), List.of(ev)));
            assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        }
    }

    @Test
    @DisplayName("RequirementCoverage COVERED 但 evIds 空 → fail (False Sufficient 防护, ctor 前置)")
    void coverageCoveredWithNoEvidenceRejected() {
        assertThatThrownBy(() -> RequirementCoverage.covered("R1", List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COVERED");
    }
}
