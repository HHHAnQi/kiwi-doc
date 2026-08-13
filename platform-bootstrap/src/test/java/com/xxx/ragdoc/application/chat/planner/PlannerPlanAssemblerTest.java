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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-7a: {@link PlannerPlanAssembler} 关键契约单测 (Translates PlannerResponse →
 * DeterministicExecutionPlan)。
 */
@DisplayName("PlannerPlanAssembler - PR-7a Plan 装配 + Planner 专属校验")
class PlannerPlanAssemblerTest {

    private PlanValidator validator;
    private PlannerProperties props;
    private PlannerPlanAssembler assembler;

    @BeforeEach
    void setup() {
        validator = mock(PlanValidator.class);
        props = new PlannerProperties();
        assembler = new PlannerPlanAssembler(validator, props);
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                new AgentBudget(3, 3, 0, 0, 30000, 0, 0, 0, java.math.BigDecimal.ZERO),
                Instant.now().plusSeconds(30),
                Set.of("semantic_search", "metadata_search"),
                20,
                4000,
                true,
                false,
                true);
    }

    private PlannerRequest request(List<EvidenceRequirement> reqs) {
        return new PlannerRequest(
                "r-1",
                "q",
                TaskIntent.MULTI_HOP,
                List.of(),
                Map.of(),
                reqs,
                EvidenceCoverageSummary.empty(),
                List.of(),
                new AgentBudgetView(3, 3, 3, 3, 30000, 1),
                List.of(
                        new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of()),
                        new PlannerToolDescriptor("metadata_search", "v1", "d", Map.of())),
                0);
    }

    private PlannerResponse response(String stepId, String tool, String reqId) {
        return new PlannerResponse(
                "plan-1",
                "v1",
                List.of(
                        new PlannedToolStep(
                                stepId,
                                tool,
                                "v1",
                                new SearchInput("q " + reqId, 5, SearchInput.SearchFilters.empty()),
                                List.of(),
                                List.of(reqId),
                                "evidence",
                                true)),
                List.of(reqId),
                PlannerResponse.INITIAL_MULTI_HOP_PLAN);
    }

    private void stubValidatorValid() {
        PlanValidationResult ok = new PlanValidationResult(true, List.of(), List.of("s1"));
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenReturn(ok);
    }

    @Test
    @DisplayName("合法 PlannerResponse → ok(plan, targetedReqIds, reasonCode)")
    void happyAssemble() {
        stubValidatorValid();
        EvidenceRequirement r1 = EvidenceRequirement.fact("R1", "d1", true);
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(r1)), response("s1", "semantic_search", "R1"), policy());

        assertThat(ar.valid()).isTrue();
        assertThat(ar.plan().steps()).hasSize(1);
        assertThat(ar.plan().steps().get(0).stepId()).isEqualTo("s1");
        assertThat(ar.targetedRequirementIds()).containsExactly("R1");
        assertThat(ar.reasonCode()).isEqualTo(PlannerResponse.INITIAL_MULTI_HOP_PLAN);
    }

    @Test
    @DisplayName("targetedRequirementIds 引用未知 Requirement → invalid (UNKNOWN_TARGETED_REQUIREMENT)")
    void unknownTargetedRequirement() {
        stubValidatorValid();
        PlannerResponse resp = response("s1", "semantic_search", "R1");
        PlannerResponse withUnknown =
                new PlannerResponse(
                        resp.planId(),
                        resp.planVersion(),
                        resp.steps(),
                        List.of("R1", "GHOST"),
                        resp.reasonCode());
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(EvidenceRequirement.fact("R1", "d", true))),
                        withUnknown,
                        policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).contains("UNKNOWN_TARGETED_REQUIREMENT");
    }

    @Test
    @DisplayName("Step.requirementIds 引用未知 Requirement → invalid")
    void stepUnknownRequirement() {
        stubValidatorValid();
        EvidenceRequirement r1 = EvidenceRequirement.fact("R1", "d1", true);
        PlannerResponse resp =
                new PlannerResponse(
                        "p",
                        "v1",
                        List.of(
                                new PlannedToolStep(
                                        "s1",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of("GHOST"),
                                        "ev",
                                        true)),
                        List.of(),
                        "");
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(request(List.of(r1)), resp, policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).contains("UNKNOWN_REQUIREMENT");
    }

    @Test
    @DisplayName("Step 数超过 maxPlanSteps (3) → invalid")
    void tooManySteps() {
        props.setMaxPlanSteps(1);
        stubValidatorValid();
        PlannerResponse resp =
                new PlannerResponse(
                        "p",
                        "v1",
                        List.of(
                                new PlannedToolStep(
                                        "s1",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of(),
                                        "",
                                        true),
                                new PlannedToolStep(
                                        "s2",
                                        "metadata_search",
                                        "v1",
                                        new SearchInput("q2", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of(),
                                        "",
                                        true)),
                        List.of(),
                        "");
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(EvidenceRequirement.fact("R1", "d", true))),
                        resp,
                        policy());
        assertThat(ar.invalidReason()).contains("TOO_MANY_STEPS");
    }

    @Test
    @DisplayName("重复 stepId → invalid")
    void duplicateStepId() {
        stubValidatorValid();
        PlannerResponse resp =
                new PlannerResponse(
                        "p",
                        "v1",
                        List.of(
                                new PlannedToolStep(
                                        "s1",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of(),
                                        "",
                                        true),
                                new PlannedToolStep(
                                        "s1",
                                        "metadata_search",
                                        "v1",
                                        new SearchInput("q2", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of(),
                                        "",
                                        true)),
                        List.of(),
                        "");
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(EvidenceRequirement.fact("R1", "d", true))),
                        resp,
                        policy());
        assertThat(ar.invalidReason()).contains("DUPLICATE_STEP_ID");
    }

    @Test
    @DisplayName("PlanValidator 返回非法 → invalid")
    void planValidatorFails() {
        PlanValidationResult bad =
                new PlanValidationResult(
                        false,
                        List.of(
                                new PlanValidationResult.PlanValidationError(
                                        "BAD", "s1", "step banned tool")),
                        List.of());
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenReturn(bad);
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(EvidenceRequirement.fact("R1", "d", true))),
                        response("s1", "semantic_search", "R1"),
                        policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).contains("PLAN_VALIDATOR_FAILED");
    }

    @Test
    @DisplayName("banned identity 字段在 stepIdContains ADMIN → stepId 验证抛,  AssemblyResult invalid")
    void bannedField() {
        // stepId "tenantid-x" 内含敏感词 → AgentToolStep ctor fail-closed
        stubValidatorValid();
        PlannerResponse resp =
                new PlannerResponse(
                        "p",
                        "v1",
                        List.of(
                                new PlannedToolStep(
                                        "tenantid-step",
                                        "semantic_search",
                                        "v1",
                                        new SearchInput("q", 5, SearchInput.SearchFilters.empty()),
                                        List.of(),
                                        List.of(),
                                        "",
                                        true)),
                        List.of(),
                        "");
        PlannerPlanAssembler.AssemblyResult ar =
                assembler.assemble(
                        request(List.of(EvidenceRequirement.fact("R1", "d", true))),
                        resp,
                        policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).contains("STEP_VALIDATION_FAILED");
    }
}
