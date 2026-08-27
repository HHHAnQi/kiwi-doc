package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetManager;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.BudgetDecision;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.ReservationResult;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
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
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import com.xxx.ragdoc.application.chat.sufficiency.RuleSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyProperties;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyRequest;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.domain.auth.Principal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * P2-D3 Agent 级语义 replan 行为验证 — 核心不变式:
 * <b>SHOULD_REPLAN → REPLAN; SHOULD_NOT_REPLAN → NO_REPLAN。</b>
 *
 * <p>与 ForcedModelReplanIntegrationTest(D1/D2 命名空间回归)同构的装配:
 * 真实 Coordinator/ModelPlanner(脚本LLM)/Assembler/Rule+Dispatching Sufficiency/
 * Guard/ReplanDecision/PhaseExecutor; 仅 DB/Tool体/runFactory/finalizer/ModelJudge为mock。
 * ModelJudge 脚本携带"语义判定"(relevant-but-insufficient → NOT_COVERED),
 * 这是 D3 修复赋予它的职责。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2-D3 Agent级 T1-T4 — 语义充分性驱动的 replan 双向验证")
class D3SemanticReplanBehaviorTest {

    private static final String RUN_ID = "r-d3-t";
    private static final String TENANT = "tA";

    @Mock private com.xxx.ragdoc.application.chat.port.ChatClient chatClient;
    @Mock private ToolExecutor toolExecutor;
    @Mock private AgentPersistenceCoordinator persistence;
    @Mock private AgentRunFactory runFactory;
    @Mock private PlannedAgentRunFinalizer runFinalizer;
    @Mock private ModelSufficiencyJudge modelJudge;

    private PlannedAgentExecutionCoordinator coordinator;
    private final List<String> toolQueries = new ArrayList<>();
    private final List<String> modelVerdicts = new ArrayList<>();

    /** 每个用例注入不同的脚本化语义判定序列。 */
    private java.util.function.BiFunction<Integer, SufficiencyRequest, SufficiencyDecision>
            semanticJudge;

    @BeforeEach
    void setup() throws Exception {
        PlannerProperties plannerProps = new PlannerProperties();
        ModelPlannerProvider modelPlanner =
                new ModelPlannerProvider(chatClient, new ObjectMapper(), plannerProps);
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(
                        any(DeterministicExecutionPlan.class), any(AgentExecutionPolicy.class)))
                .thenReturn(new PlanValidationResult(true, List.of(), List.of("s0")));
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
                        new PlannerPlanAssembler(validator, plannerProps),
                        runFactory,
                        phaseExecutor,
                        sufficiency,
                        new ReplanDecisionCoordinator(new AgentProgressDetector()),
                        runFinalizer,
                        new com.xxx.ragdoc.application.chat.planned.SufficiencyDecisionGuard(),
                        persistence);

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
        when(runFinalizer.finalize(
                        anyString(), anyLong(), anySet(), any(), anyString(), any(), any(), any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 6L, AgentRunStatus.READY_TO_ANSWER));

        // planner: initial 始终 1 步(REQ-1, Seata查询); replan 1 步(REQ-2, Nacos查询)
        String initial =
                """
                {"planId":"p1","planVersion":"v1","steps":[{"stepId":"a","toolName":"semantic_search",
                 "toolVersion":"v1","input":{"query":"Seata AT mode mechanism","topK":5},"dependsOn":[],
                 "requirementIds":["REQ-1"],"expectedEvidence":"e","required":true}],
                 "targetedRequirementIds":["REQ-1"],"reasonCode":""}
                """;
        String replan =
                """
                {"planId":"p2","planVersion":"v1","steps":[{"stepId":"b","toolName":"semantic_search",
                 "toolVersion":"v1","input":{"query":"Nacos config ports","topK":5},"dependsOn":[],
                 "requirementIds":["REQ-2"],"expectedEvidence":"e","required":true}],
                 "targetedRequirementIds":["REQ-2"],"reasonCode":""}
                """;
        java.util.concurrent.atomic.AtomicInteger llm = new java.util.concurrent.atomic.AtomicInteger();
        when(chatClient.chat(anyString(), anyList()))
                .thenAnswer(inv -> llm.incrementAndGet() == 1 ? initial : replan);

        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenAnswer(
                        inv -> {
                            ToolInput in = inv.getArgument(2);
                            String q = in instanceof SearchInput si ? si.query() : "";
                            toolQueries.add(q);
                            String content = q.toLowerCase().contains("seata")
                                    ? "Seata AT mode uses undo_log table"
                                    : "Nacos default ports 8848 and 9848";
                            Evidence e =
                                    Evidence.of(TENANT, 1L, 10L, "v1", content, 0.9, null,
                                            "semantic_search", Map.of());
                            return ToolResult.success(
                                    "c-" + toolQueries.size(), "semantic_search", "v1",
                                    new Out(List.of(e)), 10L, Map.of());
                        });

        // 脚本化语义判定: 由各用例的 semanticJudge 决定 verdict 序列
        java.util.concurrent.atomic.AtomicInteger mj = new java.util.concurrent.atomic.AtomicInteger();
        when(modelJudge.evaluate(any()))
                .thenAnswer(
                        inv -> {
                            SufficiencyRequest req = inv.getArgument(0);
                            SufficiencyDecision d =
                                    semanticJudge.apply(mj.incrementAndGet(), req);
                            modelVerdicts.add(
                                    d.status() == SufficiencyStatus.SUFFICIENT
                                            ? "SUFFICIENT"
                                            : "INSUFFICIENT");
                            return d;
                        });
    }

    private record Out(List<Evidence> evidences)
            implements com.xxx.ragdoc.application.chat.tool.ToolOutput,
            com.xxx.ragdoc.application.chat.tool.EvidenceListOutput {
        @Override
        public List<Evidence> evidences() {
            return evidences;
        }

        @Override
        public com.xxx.ragdoc.application.chat.tool.EvidenceListOutput withEvidences(
                List<Evidence> e) {
            return new Out(e);
        }
    }

    /** 按 requirementId 生成 COVERED 决策(引用真实 evidenceId, 过 Guard 授权校验)。 */
    private static SufficiencyDecision verdict(
            SufficiencyRequest req, Set<String> coveredIds) {
        List<RequirementCoverage> covs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (var r : req.requirements()) {
            List<Evidence> hits =
                    req.evidence().stream()
                            .filter(
                                    e -> {
                                        Object ids =
                                                e.metadata() == null
                                                        ? null
                                                        : e.metadata().get("requirementIds");
                                        return ids instanceof List<?> l
                                                && l.contains(r.requirementId());
                                    })
                            .toList();
            if (coveredIds.contains(r.requirementId()) && !hits.isEmpty()) {
                covs.add(
                        RequirementCoverage.covered(
                                r.requirementId(),
                                List.of(hits.get(0).evidenceId()),
                                "SCRIPTED"));
            } else if (coveredIds.contains(r.requirementId()) && !req.evidence().isEmpty()) {
                covs.add(
                        RequirementCoverage.covered(
                                r.requirementId(),
                                List.of(req.evidence().get(0).evidenceId()),
                                "SCRIPTED_SYNTH"));
            } else {
                covs.add(RequirementCoverage.notCovered(r.requirementId(), "SCRIPTED_MISSING"));
                if (r.required()) missing.add(r.requirementId());
            }
        }
        boolean sufficient = missing.isEmpty();
        return SufficiencyDecision.model(
                sufficient ? SufficiencyStatus.SUFFICIENT : SufficiencyStatus.INSUFFICIENT,
                covs, missing, List.of(),
                sufficient ? RecommendedAction.ANSWER : RecommendedAction.REFUSE_NO_EVIDENCE,
                "SCRIPTED");
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                new AgentBudget(6, 12, 3, 1, 30_000L, 0, 0, 0, BigDecimal.ZERO),
                Instant.now().plusSeconds(60),
                Set.of("semantic_search", "keyword_search", "metadata_search", "document_fetch"),
                20, 4000, true, false, true);
    }

    private PlannedAgentExecutionCoordinator.PrepareResult run() {
        return coordinator.prepare(
                "Seata和Nacos的核心机制分别是什么",
                new RouterDecision(
                        TaskIntent.MULTI_HOP, ExecutionStrategy.PLANNED_AGENT,
                        List.of("Seata", "Nacos"), Map.of(), 0.95, "TEST"),
                "req-d3",
                new Principal(TENANT, "u1", Set.of(), null),
                CancellationTokenSource.CancellationToken.never(),
                List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                policy());
    }

    // ─── T1: SHOULD_NOT_REPLAN → NO_REPLAN ──────────────────────

    @Test
    @DisplayName("T1: 证据语义充分 → 不 replan(Model 判 SUFFICIENT, 无第二次检索)")
    void t1SufficientNoReplan() {
        semanticJudge = (n, req) -> verdict(req, Set.of("REQ-1", "REQ-2", "REQ-3"));
        var prepared = run();
        assertThat(prepared.ok()).isTrue();
        assertThat(toolQueries).hasSize(1); // 只有 Phase-0 检索
        verify(persistence, never()).appendReplanSteps(anyString(), anyList());
        assertThat(modelVerdicts).containsExactly("SUFFICIENT");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer).finalize(anyString(), anyLong(), anySet(), any(), reason.capture(),
                any(), any(), summary.capture());
        assertThat(reason.getValue()).isEqualTo("PLANNED_INITIAL_SUFFICIENT");
        // P2-D5 T1: 过程决策摘要(独立于生命周期reason, 不被终态覆盖)
        assertThat(summary.getValue()).isEqualTo("INITIAL_SUFFICIENT");
    }

    // ─── T2+T3: SHOULD_REPLAN → REPLAN → Phase-1 真实检索 → 恢复 ──

    @Test
    @DisplayName("T2/T3: 语义不足(有证据但不含答案) → replan → Phase-1 真实检索 → 充分后最终回答")
    void t2t3SemanticInsufficientTriggersReplanAndRecovers() {
        // 第1次: REQ-1 证据"语义不足"(SCRIPTED_MISSING) — D3 修复后这能触发 replan
        // 第2次: REQ-2 补齐 + REQ-3 合成 → SUFFICIENT
        semanticJudge =
                (n, req) -> n == 1
                        ? verdict(req, Set.of()) // 全 NOT_COVERED(语义不足)
                        : verdict(req, Set.of("REQ-1", "REQ-2", "REQ-3"));
        var prepared = run();
        assertThat(prepared.ok()).isTrue();
        // Phase-1 检索真实发生
        assertThat(toolQueries).hasSize(2);
        assertThat(toolQueries.get(1)).contains("Nacos");
        // replan 步真实落库
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentStepRecord>> appended = ArgumentCaptor.forClass(List.class);
        verify(persistence).appendReplanSteps(anyString(), appended.capture());
        assertThat(appended.getValue())
                .extracting(AgentStepRecord::stepId)
                .containsExactly("replan-1-step-0");
        assertThat(modelVerdicts).containsExactly("INSUFFICIENT", "SUFFICIENT");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer).finalize(anyString(), anyLong(), anySet(), any(), reason.capture(),
                any(), any(), summary.capture());
        assertThat(reason.getValue()).isEqualTo("PLANNED_REPLAN_SUFFICIENT");
        // P2-D5 T2/T3: replan补齐后的过程决策
        assertThat(summary.getValue()).isEqualTo("REPLAN_SUFFICIENT");
    }

    // ─── T4: Phase-1 仍不足 → bounded fallback ───────────────────

    @Test
    @DisplayName("T4: Phase-1 后仍语义不足 → 有界降级(INSUFFICIENT_AFTER_REPLAN_FALLBACK), 不再无限循环")
    void t4StillInsufficientBoundedFallback() {
        semanticJudge = (n, req) -> verdict(req, Set.of()); // 每次都语义不足
        var prepared = run();
        // 有证据 → 降级 PARTIAL 回答(不是拒答, 不是第三次检索)
        assertThat(prepared.ok()).isTrue();
        assertThat(toolQueries).hasSize(2); // bounded: maxReplans=1, 无 Phase-2
        assertThat(modelVerdicts).containsExactly("INSUFFICIENT", "INSUFFICIENT");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer).finalize(anyString(), anyLong(), anySet(), any(), reason.capture(),
                any(), any(), summary.capture());
        assertThat(reason.getValue()).isEqualTo("INSUFFICIENT_AFTER_REPLAN_FALLBACK");
        // P2-D5 T4(旧T4): replan耗尽的有界降级 — 与 REPLAN_SUFFICIENT 可区分
        assertThat(summary.getValue()).isEqualTo("REPLAN_EXHAUSTED_FALLBACK");
    }

    // ── D5 T4: CONFLICTED → REFUSED_CONFLICT + summary ──

    @Test
    @DisplayName("D5-T4: 语义判定CONFLICTED → REFUSED_CONFLICT, summary=REFUSED_CONFLICT")
    void d5_t4_conflictRefuseSummary() {
        semanticJudge =
                (n, req) ->
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.model(
                                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus
                                        .CONFLICTED,
                                List.of(), List.of(),
                                List.of(new com.xxx.ragdoc.application.chat.sufficiency
                                        .EvidenceConflict(
                                        "REQ-1",
                                        com.xxx.ragdoc.application.chat.sufficiency
                                                .EvidenceConflict.ConflictType
                                                .VERSION_VALUE_MISMATCH,
                                        List.of("ev-0", "ev-1"), "scripted")),
                                com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction
                                        .REFUSE_CONFLICT,
                                "SCRIPTED");
        var prepared = run();
        assertThat(prepared.ok()).isFalse();
        assertThat(prepared.failureTerminal())
                .isEqualTo(com.xxx.ragdoc.application.chat.agent.AgentRunStatus.REFUSED_CONFLICT);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer).finalize(anyString(), anyLong(), anySet(), any(), reason.capture(),
                any(), any(), summary.capture());
        assertThat(summary.getValue()).isEqualTo("REFUSED_CONFLICT");
    }

    // ── D5 T5: Tool failure → TOOL_FAILURE ──

    @Test
    @DisplayName("D5-T5: Phase-0工具终态失败 → TOOL_FAILED, summary=TOOL_FAILURE")
    void d5_t5_toolFailureSummary() {
        when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(
                        com.xxx.ragdoc.application.chat.tool.ToolResult.failure(
                                "c-1", "semantic_search", "v1",
                                com.xxx.ragdoc.application.chat.tool.ToolStatus.TERMINAL_ERROR,
                                com.xxx.ragdoc.application.chat.tool.ToolError.of(
                                        "TOOL_TERMINAL", "tool crashed"),
                                5L, java.util.Map.of()));
        var prepared = run();
        assertThat(prepared.ok()).isFalse();
        assertThat(prepared.failureTerminal())
                .isEqualTo(com.xxx.ragdoc.application.chat.agent.AgentRunStatus.TOOL_FAILED);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runFinalizer).finalize(anyString(), anyLong(), anySet(), any(), any(),
                any(), any(), summary.capture());
        assertThat(summary.getValue()).isEqualTo("TOOL_FAILURE");
    }
}
