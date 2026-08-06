package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import com.xxx.ragdoc.application.chat.sufficiency.EvidenceConflict;
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7c.3a: {@link SufficiencyDecisionGuard} — 进入 Answer Composer 前的硬门禁单元。 */
@DisplayName("SufficiencyDecisionGuard - PR-7c.3a False Sufficient 第三层防护")
class SufficiencyDecisionGuardTest {

    private SufficiencyDecisionGuard guard;

    @BeforeEach
    void setup() {
        guard = new SufficiencyDecisionGuard();
    }

    private EvidenceRequirement req(String id, boolean required) {
        return new EvidenceRequirement(id, "desc-" + id, RequirementType.FACT, required,
                List.of(), Map.of());
    }

    private Evidence ev(String evidenceId) {
        // Evidence.of 算 evidenceId = sha256(tenant|doc|chunk|contentHash); 我们直接构造同 evidenceId
        // 通过同 (tenant,doc,chunk,content) 即可
        return Evidence.of("tA", 1L, 10L, "v1", "content", 0.9, null, "semantic_search", Map.of());
    }

    private SufficiencyDecision sufficient(List<RequirementCoverage> cov) {
        return SufficiencyDecision.rule(SufficiencyStatus.SUFFICIENT,
                cov, List.of(), List.of(), RecommendedAction.ANSWER, "OK");
    }

    @Test
    @DisplayName("合法: required COVERED + 在授权 Evidence 列表 → allow")
    void happyAllow() {
        Evidence e = ev("x");
        SufficiencyDecision d = sufficient(List.of(
                RequirementCoverage.covered("R1", List.of(e.evidenceId()), "")));
        assertThat(guard.validateForAnswer(d, List.of(req("R1", true)), List.of(e)).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("status != SUFFICIENT → reject STATUS_NOT_SUFFICIENT")
    void statusNotSufficient() {
        SufficiencyDecision d = SufficiencyDecision.rule(
                SufficiencyStatus.INSUFFICIENT, List.of(), List.of("R1"), List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE, "");
        assertThat(guard.validateForAnswer(d, List.of(req("R1", true)), List.of()).allowed())
                .isFalse();
    }

    @Test
    @DisplayName("action != ANSWER → reject")
    void actionNotAnswer() {
        SufficiencyDecision d = SufficiencyDecision.rule(SufficiencyStatus.SUFFICIENT,
                List.of(), List.of(), List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE, ""); // action 不是 ANSWER
        assertThat(guard.validateForAnswer(d, List.of(), List.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("missingRequirementIds 非空 → reject")
    void missingNotEmpty() {
        SufficiencyDecision d = SufficiencyDecision.rule(
                SufficiencyStatus.SUFFICIENT, List.of(), List.of("R1"), List.of(),
                RecommendedAction.ANSWER, "");
        assertThat(guard.validateForAnswer(d, List.of(), List.of()).allowed()).isFalse();
    }

    @Test
    @DisplayName("conflicts 非空 → reject (SufficiencyDecision ctor 也会拒, 但 Guard 兜底)")
    void conflictsNotEmpty() {
        // SufficiencyDecision ctor 不允许 SUFFICIENT + conflicts, 这里走 CONFLICTED 应被拒
        SufficiencyDecision d = SufficiencyDecision.rule(
                SufficiencyStatus.CONFLICTED, List.of(), List.of(),
                List.of(new EvidenceConflict("R1",
                        EvidenceConflict.ConflictType.VERSION_VALUE_MISMATCH,
                        List.of("a", "b"), "x")),
                RecommendedAction.REFUSE_CONFLICT, "");
        assertThat(guard.validateForAnswer(d, List.of(req("R1", true)), List.of()).allowed())
                .isFalse();
    }

    @Test
    @DisplayName("required 缺 Coverage → reject REQUIRED_HAS_NO_COVERAGE")
    void requiredMissingCoverage() {
        Evidence e = ev("x");
        SufficiencyDecision d = sufficient(List.of()); // 没有 R1 的 coverage
        SufficiencyDecisionGuard.GuardResult r = guard.validateForAnswer(
                d, List.of(req("R1", true)), List.of(e));
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).contains("REQUIRED_HAS_NO_COVERAGE");
    }

    @Test
    @DisplayName("required Coverage 但不是 COVERED → reject")
    void requiredNotCoveredStatus() {
        SufficiencyDecision d = SufficiencyDecision.rule(
                SufficiencyStatus.SUFFICIENT,
                List.of(RequirementCoverage.notCovered("R1", "x")),
                List.of(), List.of(), RecommendedAction.ANSWER, "");
        // 上面 ctor 应该会标 missing, 但是 status SUFFICIENT + missing 空 — Phase 我们手工构造不严格
        // SufficiencyDecision ctor: status SUFFICIENT + missingIsEmpty OK
        SufficiencyDecisionGuard.GuardResult r = guard.validateForAnswer(
                d, List.of(req("R1", true)), List.of());
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).contains("REQUIRED_NOT_COVERED");
    }

    @Test
    @DisplayName("COVERED 但 evidenceId 不在授权 list → reject EVIDENCE_NOT_AUTHORIZED")
    void coveredWithUnauthorizedEvidence() {
        SufficiencyDecision d = sufficient(List.of(
                RequirementCoverage.covered("R1", List.of("unknown-ghost-id"), "")));
        SufficiencyDecisionGuard.GuardResult r = guard.validateForAnswer(
                d, List.of(req("R1", true)), List.of() /* 授权空 */);
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).contains("EVIDENCE_NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("coverage.requirementId 未知 → reject")
    void coverageUnknownReq() {
        Evidence e = ev("x");
        SufficiencyDecision d = sufficient(List.of(
                RequirementCoverage.covered("R1", List.of(e.evidenceId()), "")));
        // requirements 列表中没有 R1, 真实 R1 不存在
        SufficiencyDecisionGuard.GuardResult r = guard.validateForAnswer(
                d, List.of(/* 空 requirements */), List.of(e));
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).contains("COVERAGE_UNKNOWN_REQ");
    }

    @Test
    @DisplayName("重复同一 Requirement Coverage → reject DUPLICATE_COVERAGE")
    void duplicateCoverage() {
        Evidence e = ev("x");
        // SufficiencyDecision ctor 不去重; 手工注入两个同 R1 coverage
        SufficiencyDecision d = new SufficiencyDecision(
                SufficiencyStatus.SUFFICIENT,
                List.of(RequirementCoverage.covered("R1", List.of(e.evidenceId()), ""),
                        RequirementCoverage.covered("R1", List.of(e.evidenceId()), "")),
                List.of(), List.of(), RecommendedAction.ANSWER, "x", "RULE");
        SufficiencyDecisionGuard.GuardResult r = guard.validateForAnswer(
                d, List.of(req("R1", true)), List.of(e));
        assertThat(r.allowed()).isFalse();
        assertThat(r.reasonCode()).contains("DUPLICATE_COVERAGE");
    }
}
