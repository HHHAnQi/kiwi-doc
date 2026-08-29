package com.xxx.ragdoc.application.chat.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.PlanValidationResult;
import com.xxx.ragdoc.application.chat.agent.PlanValidator;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import java.time.Instant;
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

/**
 * P2-D1/D2: Model Planner replan 路径契约测试。
 *
 * <p>D1 修复前: canonicalId 恒为 plan-step-{N} 重新编号, replan 与 Phase-0 已完成步 在 Assembler seenStepIds 必然碰撞
 * → Model replan 100% DUPLICATE_STEP_ID 失效。 D2 修复前: replan prompt 只有 tool/outcome/evidence 数, LLM
 * 无法知道已试过什么查询。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2-D1/D2 Model Replan 命名空间 + attempted-query 上下文")
class ModelReplanCanonicalizationTest {

    @Mock private ChatClient chatClient;
    private PlannerProperties props;
    private ModelPlannerProvider provider;
    private PlannerPlanAssembler assembler;

    @BeforeEach
    void setup() {
        props = new PlannerProperties();
        provider = new ModelPlannerProvider(chatClient, new ObjectMapper(), props);
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenReturn(new PlanValidationResult(true, List.of(), List.of("s0")));
        assembler = new PlannerPlanAssembler(validator, props);
    }

    private PlannerRequest req(int replanIndex, List<CompletedStepSummary> completed) {
        return new PlannerRequest(
                "run-1",
                "multi hop question",
                TaskIntent.MULTI_HOP,
                List.of(),
                Map.of(),
                List.of(EvidenceRequirement.fact("REQ-1", "d", true)),
                EvidenceCoverageSummary.empty(),
                completed,
                new AgentBudgetView(6, 12, 6, 12, 30000, 1),
                List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                replanIndex);
    }

    /** LLM 原始输出: 两个 step, 第二个 dependsOn 第一个(LLM 自有 id)。 */
    private String llmJson(String q1, String q2) {
        return """
                {"planId":"p1","planVersion":"v1","steps":[
                 {"stepId":"llm-A","toolName":"semantic_search","toolVersion":"v1",
                  "input":{"query":"%s","topK":5},"dependsOn":[],
                  "requirementIds":["REQ-1"],"expectedEvidence":"e","required":true},
                 {"stepId":"llm-B","toolName":"keyword_search","toolVersion":"v1",
                  "input":{"query":"%s","topK":5},"dependsOn":["llm-A"],
                  "requirementIds":["REQ-1"],"expectedEvidence":"e","required":true}],
                 "targetedRequirementIds":["REQ-1"],"reasonCode":""}
                """
                .formatted(q1, q2);
    }

    private void stubLlm(String json) throws Exception {
        when(chatClient.chat(anyString(), anyList())).thenReturn("```json\n" + json + "\n```");
    }

    private CompletedStepSummary completed(String stepId, String query) {
        SearchInput in = new SearchInput(query, 5, SearchInput.SearchFilters.empty());
        return new CompletedStepSummary(
                stepId,
                "semantic_search",
                "v1",
                "semantic_search|v1|" + in.normalizedForDedup(),
                1,
                List.of("REQ-1"),
                "SUCCEEDED",
                query,
                Map.of());
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                new AgentBudget(6, 12, 3, 1, 30000, 0, 0, 0, java.math.BigDecimal.ZERO),
                Instant.now().plusSeconds(30),
                Set.of("semantic_search", "keyword_search"),
                20,
                4000,
                true,
                false,
                true);
    }

    @Test
    @DisplayName("T1: replanIndex=0 → canonical id = plan-step-0/1 (zero-diff)")
    void t1InitialNamespace() throws Exception {
        stubLlm(llmJson("alpha", "beta"));
        PlannerResponse r = provider.plan(req(0, List.of()));
        assertThat(r.steps())
                .extracting(PlannedToolStep::stepId)
                .containsExactly("plan-step-0", "plan-step-1");
    }

    @Test
    @DisplayName(
            "T2: Phase-0 已存在 plan-step-0 → replan canonical id = replan-1-step-*, Assembler 不再 DUPLICATE")
    void t2FirstReplanNamespace() throws Exception {
        stubLlm(llmJson("gamma", "delta"));
        PlannerResponse r = provider.plan(req(1, List.of(completed("plan-step-0", "alpha"))));
        assertThat(r.steps())
                .extracting(PlannedToolStep::stepId)
                .containsExactly("replan-1-step-0", "replan-1-step-1");
        var ar =
                assembler.assemble(req(1, List.of(completed("plan-step-0", "alpha"))), r, policy());
        assertThat(ar.valid()).as("invalidReason=%s", ar.invalidReason()).isTrue();
    }

    @Test
    @DisplayName("T3: 已存在 plan-step-* + replan-1-step-* → replan-2-step-* 不冲突")
    void t3SecondReplanNamespace() throws Exception {
        stubLlm(llmJson("epsilon", "zeta"));
        List<CompletedStepSummary> done =
                List.of(completed("plan-step-0", "alpha"), completed("replan-1-step-0", "beta"));
        PlannerResponse r = provider.plan(req(2, done));
        assertThat(r.steps())
                .extracting(PlannedToolStep::stepId)
                .containsExactly("replan-2-step-0", "replan-2-step-1");
        var ar = assembler.assemble(req(2, done), r, policy());
        assertThat(ar.valid()).as("invalidReason=%s", ar.invalidReason()).isTrue();
    }

    @Test
    @DisplayName("T4: dependsOn 在 canonicalization 后仍指向同 plan 内正确的 replan step")
    void t4DependsOnRemapped() throws Exception {
        stubLlm(llmJson("alpha", "beta"));
        PlannerResponse r = provider.plan(req(1, List.of(completed("plan-step-0", "old"))));
        assertThat(r.steps().get(1).dependsOn())
                .as("llm-B dependsOn llm-A → 必须重映射到 replan-1-step-0")
                .containsExactly("replan-1-step-0");
    }

    @Test
    @DisplayName("T5: replan prompt 明确含 attempted query 文本(非仅 hash)")
    void t5AttemptedQueryVisible() {
        String prompt =
                ModelPlannerProvider.buildPrompt(
                        req(1, List.of(completed("plan-step-0", "Seata AT mode mechanism"))),
                        props.getMaxPlanSteps());
        assertThat(prompt)
                .contains("attempted_query=\"Seata AT mode mechanism\"")
                .contains("DIFFERENT")
                .doesNotContain("toolSignatureHash");
    }

    @Test
    @DisplayName("T6: 模型仍生成完全相同 query → Assembler 保持 PLAN_REPEATED_TOOL_CALL 确定性拒绝")
    void t6RepeatedQueryStillRejected() throws Exception {
        // D2 修复降低概率, 但 runtime 防御语义保留(P2 决策: 不改 skip-duplicate)
        stubLlm(
                llmJson(
                        "alpha",
                        "delta")); // step-0 query 与 Phase-0 completed("plan-step-0","alpha") 完全一致
        PlannerResponse r = provider.plan(req(1, List.of(completed("plan-step-0", "alpha"))));
        var ar =
                assembler.assemble(req(1, List.of(completed("plan-step-0", "alpha"))), r, policy());
        assertThat(ar.valid()).isFalse();
        assertThat(ar.invalidReason()).contains("PLAN_REPEATED_TOOL_CALL");
    }
}
