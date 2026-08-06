package com.xxx.ragdoc.application.chat.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.AgentToolStep;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
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

/**
 * PR-6c / EMS-PR6c §14.1: {@link ComparisonPlanFactory} 单测。
 */
@DisplayName("ComparisonPlanFactory - PR-6c.1 服务端确定性 Plan 构造")
class ComparisonPlanFactoryTest {

    private ComparisonPlanFactory factory;

    @BeforeEach
    void setup() {
        ComparisonExecutorProperties props = new ComparisonExecutorProperties();
        factory = new ComparisonPlanFactory(props);
    }

    private RouterDecision decision(List<String> entities, Map<String, Object> filters) {
        return new RouterDecision(
                TaskIntent.COMPARISON, ExecutionStrategy.FIXED_WORKFLOW,
                entities == null ? List.of() : entities,
                filters == null ? Map.of() : filters,
                1.0, "TWO_VERSION_COMPARE");
    }

    @Nested
    @DisplayName("Tool 选择规则 (§5.3)")
    class ToolChoice {

        @Test
        @DisplayName("两个版本 → 两个 metadata_search Step")
        void versionPairMetadata() {
            ComparisonPlanBuildResult r = factory.build(
                    "对比 v1.0 与 v2.0 的权限",
                    decision(List.of(), Map.of("versions", List.of("v1.0", "v2.0"))),
                    Map.of());
            assertThat(r.valid()).isTrue();
            assertThat(r.leftToolChoice().toolName()).isEqualTo("metadata_search");
            assertThat(r.rightToolChoice().toolName()).isEqualTo("metadata_search");
            // SearchInput filters.version
            SearchInput li = (SearchInput) r.plan().steps().get(0).input();
            assertThat(li.filters().version()).isEqualTo("v1.0");
            SearchInput ri = (SearchInput) r.plan().steps().get(1).input();
            assertThat(ri.filters().version()).isEqualTo("v2.0");
        }

        @Test
        @DisplayName("两个普通实体 (无结构化 filter) → 两个 semantic_search Step")
        void genericEntitiesSemantic() {
            ComparisonPlanBuildResult r = factory.build(
                    "对比产品 A 与产品 B 的认证机制",
                    decision(List.of("产品A", "产品B"), Map.of()),
                    Map.of());
            assertThat(r.valid()).isTrue();
            assertThat(r.leftToolChoice().toolName()).isEqualTo("semantic_search");
            assertThat(r.rightToolChoice().toolName()).isEqualTo("semantic_search");
            SearchInput li = (SearchInput) r.plan().steps().get(0).input();
            assertThat(li.query()).contains("产品A");
        }

        @Test
        @DisplayName("products filter → metadata_search 带 source")
        void productFilterMetadata() {
            ComparisonPlanBuildResult r = factory.build(
                    "对比 aws 和 gcp 的方案",
                    decision(List.of(), Map.of("products", List.of("aws", "gcp"))),
                    Map.of());
            assertThat(r.valid()).isTrue();
            assertThat(r.leftToolChoice().toolName()).isEqualTo("metadata_search");
            SearchInput li = (SearchInput) r.plan().steps().get(0).input();
            assertThat(li.filters().source()).isEqualTo("aws");
        }

        @Test
        @DisplayName("PR-6c 第一版<b>不</b>自动启用 keyword_search (§5.3)")
        void keywordSearchNotAutoEnabled() {
            ComparisonPlanBuildResult r = factory.build(
                    "对比错误码 ERR001 vs ERR002",
                    decision(List.of("ERR001", "ERR002"), Map.of()),
                    Map.of());
            assertThat(r.valid()).isTrue();
            assertThat(r.leftToolChoice().toolName()).isEqualTo("semantic_search");
            assertThat(r.rightToolChoice().toolName()).isEqualTo("semantic_search");
        }
    }

    @Nested
    @DisplayName("Step 结构 / Plan")
    class StepStructure {

        @Test
        @DisplayName("两个 Step 都 required, 无相互依赖")
        void bothRequiredNoDeps() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare x vs y",
                    decision(List.of("x", "y"), Map.of()), Map.of());
            assertThat(r.plan().steps()).hasSize(2);
            for (AgentToolStep s : r.plan().steps()) {
                assertThat(s.required()).isTrue();
                assertThat(s.dependsOn()).isEmpty();
            }
        }

        @Test
        @DisplayName("顺序: compare-left 在 compare-right 前; StepId 唯一")
        void leftBeforeRight() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare a vs b",
                    decision(List.of("a", "b"), Map.of()), Map.of());
            List<AgentToolStep> steps = r.plan().steps();
            assertThat(steps.get(0).stepId()).isEqualTo(ComparisonPlanFactory.LEFT_STEP_ID);
            assertThat(steps.get(1).stepId()).isEqualTo(ComparisonPlanFactory.RIGHT_STEP_ID);
        }

        @Test
        @DisplayName("PlanId 固定 comparison-workflow / v1, 不是 runId")
        void fixedPlanId() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare a vs b",
                    decision(List.of("a", "b"), Map.of()), Map.of());
            assertThat(r.plan().planId()).isEqualTo(ComparisonPlanFactory.PLAN_ID);
            assertThat(r.plan().planVersion()).isEqualTo("v1");
        }

        @Test
        @DisplayName("Budget: maxSteps=2 maxToolCalls=2 planner=0")
        void budgetFixedServer() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare a vs b", decision(List.of("a", "b"), Map.of()), Map.of());
            AgentExecutionPolicy p = r.policy();
            assertThat(p.budget().maxSteps()).isEqualTo(2);
            assertThat(p.budget().maxToolCalls()).isEqualTo(2);
            assertThat(p.budget().maxPlannerCalls()).isZero();
            assertThat(p.budget().maxReplans()).isZero();
        }

        @Test
        @DisplayName("allowlist 仅含本 Plan 用到的 Tool 子集")
        void allowlistExactSubset() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare a vs b", decision(List.of("a", "b"), Map.of()), Map.of());
            assertThat(r.policy().allowedTools()).containsExactlyInAnyOrder("semantic_search");
        }

        @Test
        @DisplayName("客户端无法注入 tenant — SearchInput.filters 不含 tenantId 字段")
        void noTenantInFilters() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare a vs b",
                    decision(List.of("a", "b"), Map.of()),
                    Map.of("tenantId", "ATTACKER")); // 客户端试图覆盖
            assert r.valid();
            SearchInput li = (SearchInput) r.plan().steps().get(0).input();
            // SearchFilters 只有 source/version/language, 无 tenantId 字段 (编译期保证); 这里再加 assertion
            assertThat(li.filters().source()).isNull();
            assertThat(li.filters().version()).isNull();
            assertThat(r.leftTarget().filters()).doesNotContainKey("tenantId");
        }
    }

    @Nested
    @DisplayName("失败路径")
    class Failures {

        @Test
        @DisplayName("entities 不足 (< 2) → INSUFFICIENT_TARGETS")
        void insufficientTargets() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare alone", decision(List.of("a"), Map.of()), Map.of());
            assertThat(r.valid()).isFalse();
            assertThat(r.invalidReason()).isEqualTo("INSUFFICIENT_TARGETS");
        }

        @Test
        @DisplayName("两实体规范化后相同 → DUPLICATE_TARGETS_NORMALIZED")
        void duplicateNormalized() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare A vs a", decision(List.of("A", "a"), Map.of()), Map.of());
            assertThat(r.valid()).isFalse();
            assertThat(r.invalidReason()).isEqualTo("DUPLICATE_TARGETS_NORMALIZED");
        }

        @Test
        @DisplayName("空 query → EMPTY_QUERY")
        void emptyQuery() {
            ComparisonPlanBuildResult r = factory.build(
                    "   ", decision(List.of("a", "b"), Map.of()), Map.of());
            assertThat(r.valid()).isFalse();
            assertThat(r.invalidReason()).isEqualTo("EMPTY_QUERY");
        }

        @Test
        @DisplayName("多于 2 目标 → TOO_MANY_TARGETS (PR-6c v1 不扩展为 N 路)")
        void tooManyTargets() {
            ComparisonPlanBuildResult r = factory.build(
                    "compare 3 things",
                    decision(List.of("a", "b", "c"), Map.of()), Map.of());
            assertThat(r.valid()).isFalse();
            assertThat(r.invalidReason()).contains("TOO_MANY_TARGETS");
        }
    }
}
