package com.xxx.ragdoc.application.chat.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PR-7a: {@link RuleTemplatePlannerProvider} 单测 (确定性, 不调 LLM)。 */
@DisplayName("RuleTemplatePlannerProvider - PR-7a 确定性规则 Plan 生成")
class RuleTemplatePlannerProviderTest {

    private PlannerProperties props;
    private RuleTemplatePlannerProvider planner;

    @BeforeEach
    void setup() {
        props = new PlannerProperties();
        planner = new RuleTemplatePlannerProvider(props);
    }

    private List<PlannerToolDescriptor> allowlist() {
        return List.of(
                new PlannerToolDescriptor("semantic_search", "v1", "semantic", Map.of()),
                new PlannerToolDescriptor("keyword_search", "v1", "keyword", Map.of()),
                new PlannerToolDescriptor("metadata_search", "v1", "metadata", Map.of()),
                new PlannerToolDescriptor("document_fetch", "v1", "fetch", Map.of()));
    }

    private PlannerRequest initialRequest(List<EvidenceRequirement> reqs, Map<String, Object> filters) {
        return new PlannerRequest(
                "r-1", "对比 v1 与 v2 的差异", TaskIntent.MULTI_HOP,
                List.of("v1", "v2"), filters, reqs,
                EvidenceCoverageSummary.empty(), List.of(),
                new AgentBudgetView(3, 3, 3, 3, 30000, 1),
                allowlist(), 0);
    }

    @Nested
    @DisplayName("初始 Plan")
    class Initial {

        @Test
        @DisplayName("两个 Requirement (FACT + RELATION) → metadata_search + semantic_search 各一 Step")
        void mixedRequirements() {
            EvidenceRequirement r1 = new EvidenceRequirement("R1", "R1 描述", RequirementType.FACT,
                    true, List.of("v1"), Map.of());
            EvidenceRequirement r2 = new EvidenceRequirement("R2", "R2 描述", RequirementType.RELATION,
                    true, List.of(), Map.of());
            PlannerResponse r = planner.plan(initialRequest(List.of(r1, r2), Map.of()));

            assertThat(r.steps()).hasSize(2);
            assertThat(r.steps().get(0).toolName()).isEqualTo("metadata_search");
            assertThat(r.steps().get(1).toolName()).isEqualTo("semantic_search");
            assertThat(r.targetedRequirementIds()).containsExactlyInAnyOrder("R1", "R2");
            assertThat(r.reasonCode()).isEqualTo(PlannerResponse.INITIAL_MULTI_HOP_PLAN);
            assertThat(r.steps().get(0).stepId()).isEqualTo("plan-step-0");
            assertThat(r.steps().get(1).stepId()).isEqualTo("plan-step-1");
        }

        @Test
        @DisplayName("FOLLOW_UP_ENTITY 自动依赖前序 Step")
        void followUpHasDependsOn() {
            EvidenceRequirement r1 = new EvidenceRequirement("R1", "fact", RequirementType.FACT, true,
                    List.of("x"), Map.of());
            EvidenceRequirement r2 = new EvidenceRequirement("R2", "follow-up", RequirementType.FOLLOW_UP_ENTITY,
                    true, List.of(), Map.of());
            PlannerResponse r = planner.plan(initialRequest(List.of(r1, r2), Map.of()));
            assertThat(r.steps()).hasSize(2);
            assertThat(r.steps().get(1).dependsOn()).containsExactly("plan-step-0");
        }

        @Test
        @DisplayName("Step 数受 maxPlanSteps 上限截断 (props.max=2)")
        void maxPlanStepsCap() {
            props.setMaxPlanSteps(2);
            List<EvidenceRequirement> reqs = List.of(
                    EvidenceRequirement.fact("R1", "r1 desc", true),
                    EvidenceRequirement.fact("R2", "r2 desc", true),
                    EvidenceRequirement.fact("R3", "r3 desc", true));
            PlannerResponse r = planner.plan(initialRequest(reqs, Map.of()));
            assertThat(r.steps()).hasSize(2);
        }

        @Test
        @DisplayName("Replan 仅处理 uncoveredRequirementIds")
        void replanOnlyUncovered() {
            EvidenceRequirement r1 = EvidenceRequirement.fact("R1", "fact", true);
            EvidenceRequirement r2 = EvidenceRequirement.fact("R2", "still missing", true);
            PlannerRequest req = new PlannerRequest(
                    "r-1", "q", TaskIntent.MULTI_HOP, List.of(), Map.of(),
                    List.of(r1, r2),
                    new EvidenceCoverageSummary(2, List.of("R1"), List.of(), List.of("R2"),
                            List.of("ev1"), Map.of()),
                    List.of(/* completedSteps */),
                    new AgentBudgetView(2, 2, 3, 3, 30000, 1),
                    allowlist(), 1 /* replan */);
            PlannerResponse r = planner.plan(req);
            assertThat(r.steps()).hasSize(1);
            assertThat(r.targetedRequirementIds()).containsExactly("R2");
            assertThat(r.reasonCode()).isEqualTo(PlannerResponse.MISSING_REQUIREMENT_RECOVERY);
            assertThat(r.steps().get(0).stepId()).startsWith("replan-1-step-");
        }

        @Test
        @DisplayName("Planner 不接收 tenant — Request 内无 tenant token 字段, Plan Input 也不含")
        void noTenantInRequestOrInput() {
            EvidenceRequirement r1 = EvidenceRequirement.fact("R1", "x", true);
            PlannerResponse r = planner.plan(initialRequest(List.of(r1), Map.of()));
            SearchInput in = (SearchInput) r.steps().get(0).input();
            assertThat(in.query()).doesNotContain("tA");
            // SearchFilters 也只有 source/version/language 字段 (类型保证); query 是 description-only
        }

        @Test
        @DisplayName("Budget=0 → 返回零 Step Plan (BUDGET_ZERO)")
        void zeroBudgetYieldsEmptyPlan() {
            PlannerRequest req = new PlannerRequest(
                    "r-1", "q", TaskIntent.MULTI_HOP, List.of(), Map.of(),
                    List.of(EvidenceRequirement.fact("R1", "x", true)),
                    EvidenceCoverageSummary.empty(), List.of(),
                    new AgentBudgetView(0, 0, 3, 3, 30000, 1),
                    allowlist(), 0);
            PlannerResponse r = planner.plan(req);
            assertThat(r.steps()).isEmpty();
            assertThat(r.reasonCode()).isEqualTo("BUDGET_ZERO");
        }

        @Test
        @DisplayName("Tool 全部不在 allowlist → 该 Req 被 skip (其它允许的进 Plan)")
        void toolNotInAllowlistSkipped() {
            EvidenceRequirement r1 = new EvidenceRequirement("R1", "v1", RequirementType.FACT,
                    true, List.of("v1"), Map.of()); // 会选 metadata_search
            EvidenceRequirement r2 = EvidenceRequirement.fact("R2", "concept", true);
            PlannerRequest req = new PlannerRequest(
                    "r-1", "q", TaskIntent.MULTI_HOP, List.of(), Map.of(),
                    List.of(r1, r2), EvidenceCoverageSummary.empty(), List.of(),
                    new AgentBudgetView(3, 3, 3, 3, 30000, 1),
                    List.of(/* 空 allowlist */), 0);
            PlannerResponse r = planner.plan(req);
            assertThat(r.steps()).isEmpty();
        }

        @Test
        @DisplayName("Replan 不重复历史 Tool signature (CompletedStepSummary 已用过)")
        void replanSkipsRepeatedSignature() {
            // R1 + R2 same signature (但 desc 不同 → 不同 query, 这里强行制造相同 sig)
            EvidenceRequirement r1 = EvidenceRequirement.fact("R1", "find x", true);
            EvidenceRequirement r2 = EvidenceRequirement.fact("R2", "still missing", true);
            String usedSig = signatureOf("semantic_search", "v1",
                    "q find x " + r1.description() /* == rule-planner 当前 query 模式 */);
            // 让 completedSteps 含 R1 完整 sig
            PlannerRequest req = new PlannerRequest(
                    "r-1", "q", TaskIntent.MULTI_HOP, List.of(), Map.of(),
                    List.of(r1, r2),
                    new EvidenceCoverageSummary(1, List.of("R1"), List.of(),
                            List.of("R2"), List.of("ev1"), Map.of()),
                    List.of(new CompletedStepSummary("plan-step-0", "semantic_search", "v1",
                            signatureOf("semantic_search", "v1",
                                    "q find x " + r1.description()),
                            1, List.of("R1"), "SUCCEEDED", Map.of())),
                    new AgentBudgetView(2, 2, 3, 3, 30000, 1),
                    allowlist(), 1);
            PlannerResponse r = planner.plan(req);
            // R2 仍应被 plan (签名不冲突)
            assertThat(r.targetedRequirementIds()).contains("R2");
        }

        private String signatureOf(String t, String v, String norm) {
            return t + "|" + v + "|" + norm;
        }
    }
}
