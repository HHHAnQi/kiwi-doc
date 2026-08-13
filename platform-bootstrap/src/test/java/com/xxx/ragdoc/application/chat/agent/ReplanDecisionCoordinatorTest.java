package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.sufficiency.EvidenceConflict;
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7c.2: {@link ReplanDecisionCoordinator} — 最多一次 Replan + 终止条件。 */
@DisplayName("ReplanDecisionCoordinator - PR-7c.2 最多一次 Replan 判定")
class ReplanDecisionCoordinatorTest {

    private ReplanDecisionCoordinator coord;

    @BeforeEach
    void setup() {
        coord = new ReplanDecisionCoordinator(new AgentProgressDetector());
    }

    private PhaseExecutionResult successfulPhase(String runId, List<Evidence> newEvidence) {
        return new PhaseExecutionResult(
                runId,
                0,
                5L,
                List.of("s1"),
                newEvidence,
                newEvidence,
                AgentUsage.zero().incStep().incRealToolCall(),
                new AgentBudgetReservation(0, 0, 0, 0, 0, java.math.BigDecimal.ZERO),
                List.of(),
                Set.of(),
                Set.of(),
                false,
                "",
                null);
    }

    private SufficiencyDecision insufficient(String... missing) {
        return SufficiencyDecision.rule(
                SufficiencyStatus.INSUFFICIENT,
                List.of(RequirementCoverage.notCovered("R1", "n/a")),
                List.of(missing),
                List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE,
                "RULE_INSUFFICIENT");
    }

    private SufficiencyDecision sufficient() {
        return SufficiencyDecision.rule(
                SufficiencyStatus.SUFFICIENT,
                List.of(RequirementCoverage.covered("R1", List.of("ev1"), "")),
                List.of(),
                List.of(),
                RecommendedAction.ANSWER,
                "OK");
    }

    private SufficiencyDecision conflicted() {
        return SufficiencyDecision.rule(
                SufficiencyStatus.CONFLICTED,
                List.of(),
                List.of(),
                List.of(
                        new EvidenceConflict(
                                "R1",
                                EvidenceConflict.ConflictType.VERSION_VALUE_MISMATCH,
                                List.of("ev1", "ev2"),
                                "v mismatch")),
                RecommendedAction.REFUSE_CONFLICT,
                "CONFLICT");
    }

    private AgentBudget budget(int steps, int toolCalls) {
        return new AgentBudget(steps, toolCalls, 0, 0, 30_000L, 0, 0, 0, java.math.BigDecimal.ZERO);
    }

    private Evidence ev(String tenant, String content) {
        return Evidence.of(tenant, 1L, 10L, "v1", content, 0.9, null, "semantic_search", Map.of());
    }

    @Test
    @DisplayName("允许: INSUFFICIENT + missing 非空 + replanCount=0 + 有进展 → ALLOW")
    void allowReplan() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "new")));
        SufficiencyDecision s = insufficient("R1");
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        s,
                        Set.of(/* prior accumulated */ ),
                        EvidenceCoverageSummary.empty(),
                        0 /* replanCount */,
                        1 /* maxReplans */,
                        false /* cancel */,
                        budget(3, 3));
        assertThat(d.allowed()).isTrue();
    }

    @Test
    @DisplayName("拒绝: SUFFICIENT → noReplanNeeded (READY_TO_ANSWER)")
    void sufficientDoesNotReplan() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        sufficient(),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.READY_TO_ANSWER);
    }

    @Test
    @DisplayName("拒绝: replanCount >= maxReplans → REFUSED_NO_EVIDENCE REPLAN_EXHAUSTED")
    void replanExhausted() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insufficient("R1"),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        1 /* replanCount=1 */,
                        1 /* maxReplans */,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.REFUSED_NO_EVIDENCE);
        assertThat(d.reasonIfRefused()).isEqualTo("REPLAN_EXHAUSTED");
    }

    @Test
    @DisplayName("拒绝: 无进展 (NO_PROGRESS) → REFUSED_NO_EVIDENCE AGENT_NO_PROGRESS")
    void noProgressRefused() {
        // Phase 中 newEvidence 为空 (这就触发 NO_PROGRESS)
        PhaseExecutionResult phase = successfulPhase("r1", List.of());
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insufficient("R1"),
                        Set.of() /* prior accumulated */,
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.REFUSED_NO_EVIDENCE);
        assertThat(d.reasonIfRefused()).isEqualTo("AGENT_NO_PROGRESS");
    }

    @Test
    @DisplayName("拒绝: CONFLICTED → REFUSED_CONFLICT")
    void conflictRefused() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        conflicted(),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.REFUSED_CONFLICT);
    }

    @Test
    @DisplayName("拒绝: cancel → CANCELLED (USER_CANCELLED)")
    void cancelRefused() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insufficient("R1"),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        true /* cancel */,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    @DisplayName("拒绝: Phase 内 premature=TOOL_FAILED → 直接转 TOOL_FAILED")
    void prematureToolFailed() {
        PhaseExecutionResult phase =
                new PhaseExecutionResult(
                        "r1",
                        0,
                        3L,
                        List.of("s1"),
                        List.of(),
                        List.of(),
                        AgentUsage.zero(),
                        new AgentBudgetReservation(0, 0, 0, 0, 0, java.math.BigDecimal.ZERO),
                        List.of(),
                        Set.of(),
                        Set.of(),
                        true /* requiredStepFailed */,
                        "REQUIRED_TOOL_FAILED",
                        AgentRunStatus.TOOL_FAILED /* premature */);
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insufficient("R1"),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.TOOL_FAILED);
    }

    @Test
    @DisplayName("拒绝: missingRequirementIds 空 → REFUSED_NO_EVIDENCE NO_MISSING_REQUIREMENT")
    void noMissingNotReplenable() {
        SufficiencyDecision insuffNoMissing =
                SufficiencyDecision.rule(
                        SufficiencyStatus.INSUFFICIENT,
                        List.of(),
                        List.of(),
                        List.of(),
                        RecommendedAction.REFUSE_NO_EVIDENCE,
                        "no missing");
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insuffNoMissing,
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(3, 3));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.REFUSED_NO_EVIDENCE);
        assertThat(d.reasonIfRefused()).isEqualTo("NO_MISSING_REQUIREMENT");
    }

    @Test
    @DisplayName("拒绝: Budget 余额不足 (maxSteps - usedSteps <= 0) → BUDGET_EXCEEDED")
    void budgetExceeded() {
        PhaseExecutionResult phase = successfulPhase("r1", List.of(ev("tA", "x")));
        // usedSteps=1 usedToolCalls=1, budget=1/1 → 1-1=0 不允许 Replan
        ReplanDecisionCoordinator.ReplanDecision d =
                coord.decide(
                        phase,
                        insufficient("R1"),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        budget(1, 1));
        assertThat(d.allowed()).isFalse();
        assertThat(d.terminalStatusIfRefused()).isEqualTo(AgentRunStatus.BUDGET_EXCEEDED);
        assertThat(d.reasonIfRefused()).isEqualTo("REPLAN_BUDGET_INSUFFICIENT");
    }
}
