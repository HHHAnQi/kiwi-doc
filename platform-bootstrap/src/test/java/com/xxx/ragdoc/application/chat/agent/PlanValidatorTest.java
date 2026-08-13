package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.tool.AgentTool;
import com.xxx.ragdoc.application.chat.tool.ToolDescriptor;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.application.chat.tool.ToolOutput;
import com.xxx.ragdoc.application.chat.tool.ToolPermission;
import com.xxx.ragdoc.application.chat.tool.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PR-6a.1 PlanValidator 全量覆盖 (EMS-PR6a §13.1 第 1-22 条)。 */
@DisplayName("PlanValidator - PR-6a.1")
class PlanValidatorTest {

    private ToolRegistry registry;
    private PlanValidator validator;

    // Test ToolInput + ToolOutput records
    record TestInput(String query, Integer topK) implements ToolInput {}

    record TestOutput() implements ToolOutput {}

    @BeforeEach
    void setup() {
        registry = mock(ToolRegistry.class);
        AgentTool<TestInput, TestOutput> semantic =
                stubTool("semantic_search", "v1", TestInput.class, TestOutput.class);
        when(registry.getByName("semantic_search")).thenAnswer(inv -> semantic);
        validator = new PlanValidator(registry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <I extends ToolInput, O extends ToolOutput> AgentTool<I, O> stubTool(
            String name, String version, Class<I> in, Class<O> out) {
        AgentTool t = mock(AgentTool.class);
        when(t.descriptor())
                .thenReturn(
                        new ToolDescriptor(
                                name,
                                version,
                                "stub",
                                "v1",
                                "v1",
                                ToolPermission.READ_RETRIEVE,
                                Duration.ofSeconds(5),
                                10,
                                true,
                                com.xxx.ragdoc.application.chat.tool.ToolCostCategory.INDEX_READ));
        when(t.inputType()).thenReturn((Class) in);
        when(t.outputType()).thenReturn((Class) out);
        return t;
    }

    private AgentExecutionPolicy policy(String... tools) {
        return new AgentExecutionPolicy(
                AgentBudget.pr6Default(), null, Set.of(tools), 20, 4000, true, false, true);
    }

    private DeterministicExecutionPlan plan(AgentToolStep... steps) {
        return new DeterministicExecutionPlan("plan-1", "v1", List.of(steps));
    }

    private AgentToolStep step(String id, String tool) {
        return new AgentToolStep(id, tool, "v1", new TestInput("q", 5), List.of(), "", true);
    }

    // ── 合法路径 ────────────────────────────────────────

    @Nested
    @DisplayName("合法 Plan")
    class ValidPlans {

        @Test
        @DisplayName("合法单 Step → valid + 拓扑序含 1 个 step")
        void singleStepValid() {
            PlanValidationResult r =
                    validator.validate(
                            plan(step("s1", "semantic_search")), policy("semantic_search"));
            assertThat(r.valid()).isTrue();
            assertThat(r.topologicalStepOrder()).containsExactly("s1");
            assertThat(r.errors()).isEmpty();
        }

        @Test
        @DisplayName("合法多 Step → 拓扑序按依赖排列")
        void multiStepWithDepsValid() {
            AgentToolStep s1 = step("s1", "semantic_search");
            AgentToolStep s2 =
                    new AgentToolStep(
                            "s2",
                            "semantic_search",
                            "v1",
                            new TestInput("q2", 3),
                            List.of("s1"),
                            "",
                            true);
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan("p1", "v1", List.of(s1, s2)),
                            policy("semantic_search"));
            assertThat(r.valid()).isTrue();
            assertThat(r.topologicalStepOrder()).containsExactly("s1", "s2");
        }

        @Test
        @DisplayName("合法 DAG: S1 ← S2, S1 ← S3, 拓扑序 S1 在前 (原序 tie-break)")
        void dagOrderStable() {
            AgentToolStep s1 = step("s1", "semantic_search");
            AgentToolStep s2 =
                    new AgentToolStep(
                            "s2",
                            "semantic_search",
                            "v1",
                            new TestInput("q2", 3),
                            List.of("s1"),
                            "",
                            true);
            AgentToolStep s3 =
                    new AgentToolStep(
                            "s3",
                            "semantic_search",
                            "v1",
                            new TestInput("q3", 3),
                            List.of("s1"),
                            "",
                            true);
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan("p1", "v1", List.of(s1, s2, s3)),
                            policy("semantic_search"));
            assertThat(r.valid()).isTrue();
            // s1 先, 然后 s2, s3 按 plan 原序
            assertThat(r.topologicalStepOrder()).containsExactly("s1", "s2", "s3");
        }

        @Test
        @DisplayName("多次校验同 Plan → 拓扑序稳定")
        void topoStable() {
            DeterministicExecutionPlan p =
                    plan(
                            step("s1", "semantic_search"),
                            new AgentToolStep(
                                    "s2",
                                    "semantic_search",
                                    "v1",
                                    new TestInput("q2", 3),
                                    List.of("s1"),
                                    "",
                                    true));
            PlanValidationResult r1 = validator.validate(p, policy("semantic_search"));
            PlanValidationResult r2 = validator.validate(p, policy("semantic_search"));
            assertThat(r1.topologicalStepOrder()).isEqualTo(r2.topologicalStepOrder());
        }

        @Test
        @DisplayName("throwIfInvalid 不抛异常")
        void throwIfInvalidNoOp() {
            validator
                    .validate(plan(step("s1", "semantic_search")), policy("semantic_search"))
                    .throwIfInvalid();
        }
    }

    // ── Plan 基础 ──────────────────────────────────────

    @Nested
    @DisplayName("Plan 基础规则")
    class PlanBasics {

        @Test
        @DisplayName("空 planId → ctor 拒绝 (DeterministicExecutionPlan 构造防御)")
        void planIdMissing() {
            assertThatThrownBy(
                            () ->
                                    new DeterministicExecutionPlan(
                                            "", "v1", List.of(step("s1", "semantic_search"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空 planVersion → ctor 赋默认 'v1' (向后兼容)")
        void planVersionMissing() {
            DeterministicExecutionPlan p =
                    new DeterministicExecutionPlan(
                            "p1", "", List.of(step("s1", "semantic_search")));
            assertThat(p.planVersion()).isEqualTo("v1"); // 缺省赋值
        }

        @Test
        @DisplayName("空 steps → PLAN_EMPTY")
        void planEmptySteps() {
            assertThatThrownBy(() -> new DeterministicExecutionPlan("p1", "v1", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("超出 maxSteps → PLAN_TOO_MANY_STEPS")
        void tooManySteps() {
            AgentExecutionPolicy tiny =
                    new AgentExecutionPolicy(
                            new AgentBudget(2, 5, 0, 0, 30000, 0, 0, 0, java.math.BigDecimal.ZERO),
                            null,
                            Set.of("semantic_search"),
                            20,
                            4000,
                            true,
                            false,
                            true);
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            step("s1", "semantic_search"),
                                            step("s2", "semantic_search"),
                                            step("s3", "semantic_search"))),
                            tiny);
            assertThat(r.errors()).anyMatch(e -> e.code().equals("PLAN_TOO_MANY_STEPS"));
        }
    }

    // ── StepId ─────────────────────────────────────────

    @Nested
    @DisplayName("StepId 校验")
    class StepIdChecks {

        @Test
        @DisplayName("重复 stepId (大小写无关) → DUPLICATE_STEP_ID")
        void duplicateStepId() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            step("s1", "semantic_search"),
                                            step("S1", "semantic_search"))), // 大写折叠同 id
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("DUPLICATE_STEP_ID"));
        }

        @Test
        @DisplayName("stepId 含路径分隔符 → BANNED_STEP_ID")
        void bannedStepIdPath() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "../../etc",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q", 5),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("BANNED_STEP_ID"));
        }

        @Test
        @DisplayName("stepId 含 identity 词 (避开 AgentToolStep ctor 词条) → BANNED_STEP_ID")
        void bannedStepIdIdentity() {
            // AgentToolStep ctor 用 stepId.contains("tenantid"/"userid"/"token") 拦截三个词;
            // 用 'principal_steal' 触发 PlanValidator 更广 BANNED_INPUT_FIELDS 列表里的 principal
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "principal_steal",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q", 5),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("BANNED_STEP_ID"));
        }
    }

    // ── Tool 校验 ──────────────────────────────────────

    @Nested
    @DisplayName("Tool 校验")
    class ToolChecks {

        @Test
        @DisplayName("Tool 不存在 → TOOL_NOT_FOUND")
        void toolNotFound() {
            when(registry.getByName("ghost"))
                    .thenThrow(
                            new com.xxx.ragdoc.common.exception.DomainException(
                                    com.xxx.ragdoc.common.exception.ErrorCode.TOOL_NOT_FOUND, "x"));
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "ghost",
                                                    "v1",
                                                    new TestInput("q", 5),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search", "ghost"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
        }

        @Test
        @DisplayName("Tool 版本不匹配 → TOOL_VERSION_MISMATCH")
        void toolVersionMismatch() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v9",
                                                    new TestInput("q", 5),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("TOOL_VERSION_MISMATCH"));
        }

        @Test
        @DisplayName("Tool 不在 allowlist → TOOL_NOT_ALLOWED")
        void toolNotAllowed() {
            PlanValidationResult r =
                    validator.validate(
                            plan(step("s1", "semantic_search")),
                            policy("metadata_search")); // allowlist 不含 semantic_search
            assertThat(r.errors()).anyMatch(e -> e.code().equals("TOOL_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("Input 类型错误 → INPUT_TYPE_MISMATCH")
        void inputTypeMismatch() {
            // 用错误 ToolInput 类型
            record WrongInput(String foo) implements ToolInput {}
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v1",
                                                    new WrongInput("bar"),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("INPUT_TYPE_MISMATCH"));
        }

        @Test
        @DisplayName("Input 含 banned 字段 → BANNED_INPUT_FIELD")
        void bannedInputField() {
            record BannedInput(String query, String token) implements ToolInput {
                @Override
                public String toString() {
                    return "BannedInput[query=" + query + ", token=" + token + "]";
                }
            }
            // 注: Tool 期望 TestInput, 传 BannedInput 触发 INPUT_TYPE_MISMATCH 在 banned 检测之前;
            // 让 Tool registry 放宽 input type 来验证 banned 检测: 我们直接用 toString 含 tenantId= 的 input
            record TenantInput(String query, String tenantId) implements ToolInput {
                @Override
                public String toString() {
                    return "TenantInput[query=" + query + ", tenantId=" + tenantId + "]";
                }
            }
            // 注册 stubTool 接受 TenantInput
            AgentTool<TenantInput, TestOutput> tool =
                    stubTool("tenant_aware_tool", "v1", TenantInput.class, TestOutput.class);
            when(registry.getByName("tenant_aware_tool")).thenAnswer(inv -> tool);
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "tenant_aware_tool",
                                                    "v1",
                                                    new TenantInput("q", "tenant-A"),
                                                    List.of(),
                                                    "",
                                                    true))),
                            policy("semantic_search", "tenant_aware_tool"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("BANNED_INPUT_FIELD"));
        }
    }

    // ── 依赖校验 ────────────────────────────────────────

    @Nested
    @DisplayName("Dependency 校验")
    class DependencyChecks {

        @Test
        @DisplayName("dependency 指向不存在的 Step → DEPENDENCY_NOT_FOUND")
        void dependencyNotFound() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q", 5),
                                                    List.of("ghost"),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("DEPENDENCY_NOT_FOUND"));
        }

        @Test
        @DisplayName("self dependency → SELF_DEPENDENCY")
        void selfDependency() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q", 5),
                                                    List.of("s1"),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("SELF_DEPENDENCY"));
        }

        @Test
        @DisplayName("重复 dependency → DUPLICATE_DEPENDENCY")
        void duplicateDependency() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            step("s1", "semantic_search"),
                                            new AgentToolStep(
                                                    "s2",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q2", 3),
                                                    List.of("s1", "s1"),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("DUPLICATE_DEPENDENCY"));
        }

        @Test
        @DisplayName("二节点环 → CYCLIC_DEPENDENCY")
        void twoNodeCycle() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q1", 3),
                                                    List.of("s2"),
                                                    "",
                                                    true),
                                            new AgentToolStep(
                                                    "s2",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q2", 3),
                                                    List.of("s1"),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("CYCLIC_DEPENDENCY"));
        }

        @Test
        @DisplayName("多节点环 → CYCLIC_DEPENDENCY")
        void threeNodeCycle() {
            PlanValidationResult r =
                    validator.validate(
                            new DeterministicExecutionPlan(
                                    "p1",
                                    "v1",
                                    List.of(
                                            new AgentToolStep(
                                                    "s1",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q1", 3),
                                                    List.of("s3"),
                                                    "",
                                                    true),
                                            new AgentToolStep(
                                                    "s2",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q2", 3),
                                                    List.of("s1"),
                                                    "",
                                                    true),
                                            new AgentToolStep(
                                                    "s3",
                                                    "semantic_search",
                                                    "v1",
                                                    new TestInput("q3", 3),
                                                    List.of("s2"),
                                                    "",
                                                    true))),
                            policy("semantic_search"));
            assertThat(r.errors()).anyMatch(e -> e.code().equals("CYCLIC_DEPENDENCY"));
        }
    }

    // ── 累计错误 ────────────────────────────────────────

    @Test
    @DisplayName("一次返回多个 error (stepId 重复 + tool not allowed)")
    void multipleErrorsAtOnce() {
        PlanValidationResult r =
                validator.validate(
                        new DeterministicExecutionPlan(
                                "p1",
                                "v1",
                                List.of(
                                        step("s1", "metadata_search"), // 不在 policy allowlist
                                        step("S1", "metadata_search"))), // 重复 stepId (折叠 s1) + 仍
                        // TOOL_NOT_ALLOWED
                        policy("semantic_search"));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors().size()).isGreaterThanOrEqualTo(2);
        assertThat(r.errors())
                .extracting(e -> e.code())
                .contains("DUPLICATE_STEP_ID", "TOOL_NOT_ALLOWED");
    }

    @Test
    @DisplayName("合法 Plan 但 policy allowlist 排除该 Tool → 不允许")
    void policyAllowlistExcludes() {
        PlanValidationResult r =
                validator.validate(
                        plan(step("s1", "semantic_search")),
                        policy("metadata_search")); // 允许列表不含 semantic_search
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.code().equals("TOOL_NOT_ALLOWED"));
    }
}
