package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetManager;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.ReservationResult;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
import com.xxx.ragdoc.application.chat.agent.BudgetDecision;
import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.AgentProgressDetector;
import com.xxx.ragdoc.application.chat.agent.AgentRunFactory;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentStepRecord;
import com.xxx.ragdoc.application.chat.agent.AgentStepStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.application.chat.agent.AgentRunPhaseExecutor;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.EvidenceAccumulatorFactory;
import com.xxx.ragdoc.application.chat.agent.PlanValidationResult;
import com.xxx.ragdoc.application.chat.agent.PlanValidator;
import com.xxx.ragdoc.application.chat.agent.ReplanDecisionCoordinator;
import com.xxx.ragdoc.application.chat.tool.ToolExecutor;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.ModelPlannerProvider;
import com.xxx.ragdoc.application.chat.planner.PlannerPlanAssembler;
import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.sufficiency.DispatchingSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.ModelSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.RuleSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyProperties;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.domain.auth.Principal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P2-D1/D2 T7: 强制触发 Model Planner Replan 的 deterministic 集成测试。
 *
 * <p>真实组件: Coordinator / ModelPlannerProvider(脚本化LLM) / PlannerPlanAssembler /
 * RuleSufficiencyJudge(经 DispatchingSufficiencyJudge) / SufficiencyDecisionGuard /
 * ReplanDecisionCoordinator / AgentRunPhaseExecutor(真实预算+证据累积+签名+requirement打标)。
 * 仅 DB(persistence) / Tool 执行体 / runFactory / finalizer 为 mock。
 *
 * <p>强制路径: 多实体 MULTI_HOP → REQ-1/2(ENTITY_ATTRIBUTE)+REQ-3(RELATION);
 * Phase-0 只规划 REQ-1 检索(PARTIAL, REQ-2 未覆盖) → replan ALLOW →
 * Model replan 返回 REQ-2 新查询 → Phase-1 真实执行第二次检索 → 降级 PARTIAL 回答。
 * (真实 workload 中 D3 使 replan 自然不可达, 故必须 fixture 强制。)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2-D1/D2 T7 强制Replan — Model Planner bounded replan path 全链")
class ForcedModelReplanIntegrationTest {

    private static final String RUN_ID = "r-forced-replan-1";
    private static final String TENANT = "tA";

    @Mock private com.xxx.ragdoc.application.chat.port.ChatClient chatClient;
    @Mock private ToolExecutor toolExecutor;
    @Mock private AgentPersistenceCoordinator persistence;
    @Mock private AgentRunFactory runFactory;
    @Mock private PlannedAgentRunFinalizer runFinalizer;
    @Mock private ModelSufficiencyJudge modelJudge; // 本路径不可达(RELATION 无证据)

    private final List<String> llmPrompts = new ArrayList<>();
    private final List<String> toolQueries = new ArrayList<>();

    private PlannedAgentExecutionCoordinator coordinator;

    @BeforeEach
    void setup() throws Exception {
        // ── 真实组件 ──
        PlannerProperties plannerProps = new PlannerProperties();
        plannerProps.setMaxPlanSteps(3);
        ModelPlannerProvider modelPlanner =
                new ModelPlannerProvider(chatClient, new ObjectMapper(), plannerProps);
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenAnswer(inv -> new PlanValidationResult(true, List.of(),
                        inv.getArgument(0) instanceof DeterministicExecutionPlan p
                                ? List.of() : List.of("s0")));
        PlannerPlanAssembler assembler = new PlannerPlanAssembler(validator, plannerProps);
        SufficiencyProperties suffProps = new SufficiencyProperties();
        suffProps.setEnabled(true);
        suffProps.setModelFallbackEnabled(true);
        DispatchingSufficiencyJudge sufficiency =
                new DispatchingSufficiencyJudge(new RuleSufficiencyJudge(), modelJudge, suffProps);
        AgentRunPhaseExecutor phaseExecutor =
                new AgentRunPhaseExecutor(
                        persistence, new AgentBudgetManager(), toolExecutor,
                        new EvidenceAccumulatorFactory());

        coordinator =
                new PlannedAgentExecutionCoordinator(
                        new RuleTemplateRequirementExtractor(),
                        modelPlanner,
                        plannerProps,
                        assembler,
                        runFactory,
                        phaseExecutor,
                        sufficiency,
                        new ReplanDecisionCoordinator(new AgentProgressDetector()),
                        runFinalizer,
                        new SufficiencyDecisionGuard(),
                        persistence);

        stubPersistenceHappy();
        stubRunFactory();
        stubFinalizer();
        stubScriptedLlm();
        stubScriptedTools();
    }

    // ── 脚本化组件 ──────────────────────────────────────────────

    private void stubScriptedLlm() throws Exception {
        String initial =
                """
                {"planId":"p-init","planVersion":"v1","steps":[
                 {"stepId":"llm-1","toolName":"semantic_search","toolVersion":"v1",
                  "input":{"query":"Seata AT mode mechanism","topK":5},"dependsOn":[],
                  "requirementIds":["REQ-1"],"expectedEvidence":"e","required":true}],
                 "targetedRequirementIds":["REQ-1"],"reasonCode":""}
                """;
        String replan =
                """
                {"planId":"p-replan","planVersion":"v1","steps":[
                 {"stepId":"llm-r1","toolName":"semantic_search","toolVersion":"v1",
                  "input":{"query":"Nacos config center ports","topK":5},"dependsOn":[],
                  "requirementIds":["REQ-2"],"expectedEvidence":"e","required":true}],
                 "targetedRequirementIds":["REQ-2"],"reasonCode":""}
                """;
        AtomicInteger call = new AtomicInteger();
        when(chatClient.chat(anyString(), anyList()))
                .thenAnswer(
                        inv -> {
                            llmPrompts.add(inv.getArgument(0));
                            return call.incrementAndGet() == 1 ? initial : replan;
                        });
    }

    private void stubScriptedTools() {
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenAnswer(
                        inv -> {
                            ToolInput in = inv.getArgument(2);
                            String q = in instanceof SearchInput si ? si.query() : "";
                            toolQueries.add(q);
                            boolean seata = q.toLowerCase().contains("seata");
                            String content = seata
                                    ? "Seata AT mode uses undo_log table for rollback"
                                    : "Nacos config center default ports 8848 and 9848";
                            Evidence ev =
                                    Evidence.of(TENANT, 1L, 10L, "v1", content, 0.9, null,
                                            "semantic_search", Map.of());
                            return ToolResult.success(
                                    "c-" + toolQueries.size(), "semantic_search", "v1",
                                    new StubOutput(List.of(ev)), 10L, Map.of());
                        });
    }

    private record StubOutput(List<Evidence> evidences)
            implements com.xxx.ragdoc.application.chat.tool.ToolOutput,
            com.xxx.ragdoc.application.chat.tool.EvidenceListOutput {
        @Override
        public List<Evidence> evidences() {
            return evidences;
        }

        @Override
        public com.xxx.ragdoc.application.chat.tool.EvidenceListOutput withEvidences(
                List<Evidence> e) {
            return new StubOutput(e);
        }
    }

    private void stubPersistenceHappy() {
        when(persistence.reloadStep(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                new AgentStepRecord(
                                        inv.getArgument(0), inv.getArgument(1), 0,
                                        "semantic_search", "v1", null, "h",
                                        AgentStepStatus.PENDING, 0, List.of(), null, null,
                                        false, false, false, null, null, null, null, 0));
        when(persistence.reserveStep(
                        anyString(), anyLong(), anySet(), any(),
                        any(BudgetDecision.Allowed.class), anyString(), anyLong()))
                .thenReturn(new ReservationResult(
                        new AgentBudgetReservation(1, 1, 0, 0, 0, BigDecimal.ZERO), 4L, 1L));
        when(persistence.markStepRunning(anyString(), anyString(), anyLong(), any()))
                .thenReturn(2L);
        when(persistence.settleStep(
                        anyString(), anyLong(), anySet(), any(), any(), anyInt(),
                        anyString(), anyLong(), any(), any()))
                .thenReturn(new SettlementResult(5L, 3L));
    }

    private void stubRunFactory() {
        AgentRunRecord run =
                new AgentRunRecord(
                        RUN_ID, "req-1", TENANT, "u1", "PLANNED_AGENT",
                        AgentRunStatus.EXECUTING, "p-init", "v1", "h", "{}",
                        policy().budget(), AgentBudgetReservation.zero(), AgentUsage.zero(),
                        List.of(), 0, null, "model-llm-v1", "tsv", "iv1", "LIVE",
                        null, null, 3);
        when(runFactory.create(any(), any(), any(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString()))
                .thenReturn(new InitializedRun(run, List.of()));
    }

    private void stubFinalizer() {
        when(runFinalizer.finalize(
                        anyString(), anyLong(), anySet(), any(), anyString(), any(), any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 6L, AgentRunStatus.READY_TO_ANSWER));
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                new AgentBudget(6, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO),
                Instant.now().plusSeconds(60),
                Set.of("semantic_search", "keyword_search", "metadata_search", "document_fetch"),
                20, 4000, true, false, true);
    }

    // ── T7 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("T7: PARTIAL→replan ALLOW→Model replan→Phase-1 真实第二次检索→降级回答")
    void forcedModelReplanEndToEnd() {
        RouterDecision decision =
                new RouterDecision(
                        TaskIntent.MULTI_HOP, ExecutionStrategy.PLANNED_AGENT,
                        List.of("Seata", "Nacos"), Map.of(), 0.95, "TEST");

        PlannedAgentExecutionCoordinator.PrepareResult prepared =
                coordinator.prepare(
                        "Seata和Nacos的核心机制分别是什么",
                        decision,
                        "req-forced-1",
                        new Principal(TENANT, "u1", Set.of(), null),
                        CancellationTokenSource.CancellationToken.never(),
                        List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                        policy());

        // 1) 全链成功(经 INSUFFICIENT_AFTER_REPLAN_FALLBACK 降级为 PARTIAL 回答)
        assertThat(prepared.ok()).as("failureReason=%s", safeReason(prepared)).isTrue();

        // 2) Planner LLM 恰好 2 次调用(initial + replan), Phase-1 计划真实生成并被接受
        assertThat(llmPrompts).hasSize(2);
        // 3) D2: replan prompt 携带 attempted query(而非仅 hash)
        assertThat(llmPrompts.get(1))
                .contains("attempted_query=\"Seata AT mode mechanism\"");

        // 4) Phase-1 检索真实发生: 工具被调用 2 次, 第二次是新的 Nacos 查询
        assertThat(toolQueries).hasSize(2);
        assertThat(toolQueries.get(0)).contains("Seata");
        assertThat(toolQueries.get(1)).contains("Nacos");

        // 5) D1: replan step 以 replan-1-step-* 命名空间落库, 不再 DUPLICATE_STEP_ID
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentStepRecord>> appended = ArgumentCaptor.forClass(List.class);
        verify(persistence).appendReplanSteps(anyString(), appended.capture());
        assertThat(appended.getValue())
                .extracting(AgentStepRecord::stepId)
                .containsExactly("replan-1-step-0");

        // 6) 终态经 INSUFFICIENT_AFTER_REPLAN_FALLBACK (REQ-3 RELATION 无步覆盖→PARTIAL 降级)
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer)
                .finalize(anyString(), anyLong(), anySet(), any(), reason.capture(),
                        any(), any());
        assertThat(reason.getValue()).isEqualTo("INSUFFICIENT_AFTER_REPLAN_FALLBACK");

        // 7) 两个 requirement 的证据都进入了最终回答基座
        assertThat(prepared.prepared().evidence()).hasSize(2);
    }

    private static String safeReason(PlannedAgentExecutionCoordinator.PrepareResult r) {
        return r.failureReason() == null ? "null" : r.failureReason();
    }
}
