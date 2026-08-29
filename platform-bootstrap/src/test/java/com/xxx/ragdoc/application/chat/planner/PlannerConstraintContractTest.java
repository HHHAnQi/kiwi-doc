package com.xxx.ragdoc.application.chat.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.PlanValidationResult;
import com.xxx.ragdoc.application.chat.agent.PlanValidator;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1-A(契约对齐): LLM-visible constraint 必须 = Planner assembler runtime constraint。
 *
 * <p>背景: pilot 中 prompt 注入 remainingSteps(=6) 而 Assembler cap=min(3,6)=3, LLM 合法产出 4 步被确定性拒绝(2/50
 * 用户直败)。修复后 prompt 注入 effectiveMaxSteps =min(maxPlanSteps, remainingSteps), 与 Assembler 公式逐字一致; 越界仍
 * reject。
 */
@DisplayName("P1-A Planner 步数契约对齐 — prompt 与 assembler 统一")
class PlannerConstraintContractTest {

    private PlanValidator validator;
    private PlannerProperties props;
    private PlannerPlanAssembler assembler;

    @BeforeEach
    void setup() {
        validator = mock(PlanValidator.class);
        props = new PlannerProperties();
        assembler = new PlannerPlanAssembler(validator, props);
    }

    private PlannerRequest request(int remainingSteps) {
        return new PlannerRequest(
                "r-1",
                "q",
                TaskIntent.MULTI_HOP,
                List.of(),
                Map.of(),
                List.of(EvidenceRequirement.fact("R1", "d", true)),
                EvidenceCoverageSummary.empty(),
                List.of(),
                new AgentBudgetView(remainingSteps, 6, 6, 12, 30000, 1),
                List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                0);
    }

    private PlannerResponse stepsResponse(int n) {
        List<PlannedToolStep> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(
                    new PlannedToolStep(
                            "s" + i,
                            "semantic_search",
                            "v1",
                            new SearchInput("q" + i, 5, SearchInput.SearchFilters.empty()),
                            List.of(),
                            List.of("R1"),
                            "evidence",
                            true));
        }
        return new PlannerResponse("plan-1", "v1", steps, List.of("R1"), "");
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                new AgentBudget(6, 12, 3, 1, 30000, 0, 0, 0, java.math.BigDecimal.ZERO),
                Instant.now().plusSeconds(30),
                Set.of("semantic_search"),
                20,
                4000,
                true,
                false,
                true);
    }

    private void stubValidatorValid() {
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenReturn(new PlanValidationResult(true, List.of(), List.of("s0")));
    }

    @Test
    @DisplayName("A: maxPlanSteps=3, remainingSteps=6 → prompt 显示 max 3 (非 max 6)")
    void promptShowsEffectiveCapA() {
        props.setMaxPlanSteps(3);
        String prompt = ModelPlannerProvider.buildPrompt(request(6), props.getMaxPlanSteps());
        assertThat(prompt).contains("- max 3 steps");
        assertThat(prompt).doesNotContain("- max 6 steps");
    }

    @Test
    @DisplayName("B: maxPlanSteps=6, remainingSteps=2 → prompt 显示 max 2 (budget 收紧时跟随)")
    void promptShowsEffectiveCapB() {
        props.setMaxPlanSteps(6);
        String prompt = ModelPlannerProvider.buildPrompt(request(2), props.getMaxPlanSteps());
        assertThat(prompt).contains("- max 2 steps");
        assertThat(prompt).doesNotContain("- max 6 steps");
    }

    @Test
    @DisplayName("C: maxPlanSteps=3, remainingSteps=6, LLM 返回 3 步 → assembler 接受")
    void assemblerAcceptsWithinCap() {
        props.setMaxPlanSteps(3);
        stubValidatorValid();
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(request(6), stepsResponse(3), policy());
        assertThat(ar.valid()).isTrue();
        assertThat(ar.plan().steps()).hasSize(3);
    }

    @Test
    @DisplayName("D: maxPlanSteps=3, remainingSteps=6, LLM 仍返回 4 步 → assembler 保持确定性 reject")
    void assemblerStillRejectsOverCap() {
        props.setMaxPlanSteps(3);
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(request(6), stepsResponse(4), policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).isEqualTo("PLAN_TOO_MANY_STEPS: 4>3");
    }

    @Test
    @DisplayName("E: 契约一致性 — prompt 值与 assembler cap 在四种参数组合下逐字相等")
    void contractEquivalence() {
        stubValidatorValid();
        for (int maxPlan : new int[] {2, 3, 5, 6}) {
            for (int remaining : new int[] {1, 2, 3, 6, 8}) {
                props.setMaxPlanSteps(maxPlan);
                int expected = Math.min(maxPlan, remaining);
                String prompt = ModelPlannerProvider.buildPrompt(request(remaining), maxPlan);
                assertThat(prompt).contains("- max " + expected + " steps");
                PlannerPlanAssembler.AssemblyResult at =
                        assembler.assemble(request(remaining), stepsResponse(expected), policy());
                assertThat(at.valid())
                        .as("maxPlan=%d remaining=%d expected=%d", maxPlan, remaining, expected)
                        .isTrue();
                PlannerPlanAssembler.AssemblyResult over =
                        assembler.assemble(
                                request(remaining), stepsResponse(expected + 1), policy());
                assertThat(over.valid())
                        .as("over-cap must reject: maxPlan=%d remaining=%d", maxPlan, remaining)
                        .isFalse();
            }
        }
    }
}
