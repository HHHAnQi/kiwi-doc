package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** P1-B: Agent 域指标接线单测 — replan / budget_denied 两个指标各只有一个权威记录点, 每次决策恰一笔、Allowed/非 Denied 不计数。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P1-B Agent指标接线 - replan/budget_denied 权威点")
class AgentMetricsWiringTest {

    @Mock private MetricsPort metrics;
    private ReplanDecisionCoordinator coord;
    private AgentBudgetManager budgetManager;

    @BeforeEach
    void setup() {
        coord = new ReplanDecisionCoordinator(new AgentProgressDetector());
        coord.setMetricsPort(metrics);
        budgetManager = new AgentBudgetManager();
        budgetManager.setMetricsPort(metrics);
    }

    private PhaseExecutionResult successfulPhase() {
        return new PhaseExecutionResult(
                "r1",
                0,
                5L,
                List.of("s1"),
                List.of(ev()),
                List.of(ev()),
                AgentUsage.zero().incStep().incRealToolCall(),
                new AgentBudgetReservation(0, 0, 0, 0, 0, BigDecimal.ZERO),
                List.of(),
                Set.of(),
                Set.of(),
                false,
                "",
                null);
    }

    private Evidence ev() {
        return Evidence.of("tA", 1L, 10L, "v1", "content", 0.9, null, "semantic_search", Map.of());
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

    private SufficiencyDecision insufficient() {
        return SufficiencyDecision.rule(
                SufficiencyStatus.INSUFFICIENT,
                List.of(RequirementCoverage.notCovered("R1", "n/a")),
                List.of("R1"),
                List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE,
                "RULE");
    }

    @Test
    @DisplayName("SUFFICIENT → 不 replan, 指标记 SUFFICIENCY_ALREADY_SUFFICIENT 恰一次")
    void replanMetricNoReplanNeeded() {
        coord.decide(
                successfulPhase(),
                sufficient(),
                new java.util.HashSet<>(),
                com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary.empty(),
                0,
                1,
                false,
                new AgentBudget(6, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO));
        verify(metrics).recordAgentReplan("SUFFICIENCY_ALREADY_SUFFICIENT");
        verify(metrics, never()).recordAgentReplan("ALLOWED");
    }

    @Test
    @DisplayName("INSUFFICIENT + 有进展 + 预算足 → ALLOWED 恰一次")
    void replanMetricAllowed() {
        var d =
                coord.decide(
                        successfulPhase(),
                        insufficient(),
                        new java.util.HashSet<>(),
                        com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary.empty(),
                        0,
                        1,
                        false,
                        new AgentBudget(6, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO));
        assertThat(d.allowed()).isTrue();
        verify(metrics).recordAgentReplan("ALLOWED");
    }

    @Test
    @DisplayName("预算拒绝 → budget_denied{dimension=MAX_STEPS} 恰一次; Allowed 不计数")
    void budgetDeniedMetric() {
        BudgetDecision denied =
                budgetManager.evaluate(
                        new AgentBudget(1, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO),
                        AgentUsage.zero(),
                        null,
                        new ReservationRequest(2, 1, 0, 0, BigDecimal.ZERO));
        assertThat(denied).isInstanceOf(BudgetDecision.Denied.class);
        verify(metrics).recordAgentBudgetDenied("MAX_STEPS");

        BudgetDecision allowed =
                budgetManager.evaluate(
                        new AgentBudget(6, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO),
                        AgentUsage.zero(),
                        null,
                        new ReservationRequest(2, 1, 0, 0, BigDecimal.ZERO));
        assertThat(allowed).isInstanceOf(BudgetDecision.Allowed.class);
        // 两次 evaluate 只有第一次 Denied 计了一笔 — Allowed 不重复计数
        verify(metrics, org.mockito.Mockito.times(1)).recordAgentBudgetDenied("MAX_STEPS");
    }
}
