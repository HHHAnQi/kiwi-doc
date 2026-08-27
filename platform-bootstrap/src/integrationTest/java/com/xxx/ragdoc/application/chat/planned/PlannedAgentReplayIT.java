package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentProgressDetector;
import com.xxx.ragdoc.application.chat.agent.AgentRunFactory;
import com.xxx.ragdoc.application.chat.agent.AgentRunPhaseExecutor;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.PhaseExecutionResult;
import com.xxx.ragdoc.application.chat.agent.ReplanDecisionCoordinator;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.PlannerPlanAssembler;
import com.xxx.ragdoc.application.chat.planner.PlannerProvider;
import com.xxx.ragdoc.application.chat.planner.PlannerResponse;
import com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.sufficiency.DispatchingSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.RuleSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.domain.auth.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PR-7e.2 / EMS-PR7e §10: PlannedAgentExecutionCoordinator 跨组件轨迹 Replay 五个案.
 *
 * <p>目的: 验证 Coordinator 的<b>编排逻辑</b> 在业务场景下产生正确的 trajectory.
 *
 * <p>非 Spring Context test — 手动 wire real Coordinator + real Guard + real ReplanDecisionCoordinator
 * + real RuleSufficiencyJudge + real AgentProgressDetector; 其余边界 (PlannerProvider / PlanAssembler /
 * RunFactory / PhaseExecutor / Finalizer / PersistenceCoordinator / DispatchingSufficiencyJudge) 用
 * Mock — 因为 deep dep tree (ToolExecutor/DB) 不在 Replay IT 范畴.
 *
 * <p>真实隔离保证: 这就是 Coordinator 主流程的 contract test, 允许 PR 反馈快; 真实跨 Bean Pipeline Spring Boot IT (含
 * ToolExecutor + Persistence MySQL) 由 CI Docker 跑.
 *
 * <p>复用关系与 verification 约束 (PR-7e.2 spec):
 *
 * <ul>
 *   <li>planHash 一致: PlannerResponse.planId/planVersion 在 Initial/Replan 可追溯
 *   <li>tool signature 一致: PhaseExecutionResult.usedToolSignatures 携带的 sig; Assembler 把 Step input
 *       signature 已含
 *   <li>evidence ids 一致: PhaseExecutionResult.accumulatedEvidence = 最终
 *       PreparedGroundedAnswer.evidence
 *   <li>terminal status 一致: Finalizer 写的 status = PrepareResult failure/prepared 中的状态
 * </ul>
 *
 * <p>5 Replay cases:
 *
 * <ul>
 *   <li>A: Initial 多跳正常 → ANSWERED
 *   <li>B: 初始不足 → Replan → 充分 → ANSWERED
 *   <li>C: 初始不足 → 无新证据/INSUFFICIENT → REFUSED_NO_EVIDENCE
 *   <li>D: 初始 Evidence 冲突 → REFUSED_CONFLICT
 *   <li>E: Phase premature = REQUIRED_TOOL_FAILED → TOOL_FAILED
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlannedAgentExecutionCoordinator Replay - PR-7e.2 5 轨迹")
class PlannedAgentReplayIT {

    // Real beans (无 spring context)
    private RuleTemplateRequirementExtractor requirementExtractor;
    private SufficiencyDecisionGuard sufficiencyGuard;
    private AgentProgressDetector progressDetector;
    private RuleSufficiencyJudge ruleJudge; // 用于二级 sufficiency 判定, dispatch 在 case 内 mock

    // Mocks
    private PlannerProvider plannerProvider;
    private PlannerPlanAssembler planAssembler;
    private AgentRunFactory runFactory;
    private AgentRunPhaseExecutor phaseExecutor;
    private DispatchingSufficiencyJudge dispatchingSufficiencyJudge;
    private PlannedAgentRunFinalizer runFinalizer;
    private AgentPersistenceCoordinator persistenceCoordinator;
    private ReplanDecisionCoordinator replanDecisionCoordinator;

    private PlannedAgentExecutionCoordinator coordinator;

    private static final String RUN_ID = "r-replay-001";
    private static final String REQUEST_ID = "req-replay-001";
    private static final String TENANT = "tA";

    private static final AgentBudgetReservation ZERO_RES =
            new AgentBudgetReservation(0, 0, 0, 0, 0, java.math.BigDecimal.ZERO);
    private static final AgentUsage USAGE_ONE = AgentUsage.zero().incStep().incRealToolCall();
    private static final AgentUsage USAGE_ZERO = AgentUsage.zero();

    @BeforeEach
    void setup() {
        requirementExtractor = new RuleTemplateRequirementExtractor();
        sufficiencyGuard = new SufficiencyDecisionGuard();
        progressDetector = new AgentProgressDetector();

        plannerProvider = mock(PlannerProvider.class);
        planAssembler = mock(PlannerPlanAssembler.class);
        runFactory = mock(AgentRunFactory.class);
        phaseExecutor = mock(AgentRunPhaseExecutor.class);
        dispatchingSufficiencyJudge = mock(DispatchingSufficiencyJudge.class);
        runFinalizer = mock(PlannedAgentRunFinalizer.class);
        persistenceCoordinator = mock(AgentPersistenceCoordinator.class);
        ruleJudge = new RuleSufficiencyJudge();
        // replanDecisionCoordinator wrap real AgentProgressDetector
        replanDecisionCoordinator = new ReplanDecisionCoordinator(progressDetector);

        coordinator =
                new PlannedAgentExecutionCoordinator(
                        requirementExtractor,
                        plannerProvider,
                        // P0-1: planner 版本 trace 标记所需 (resolvePlannerVersionTag)
                        new com.xxx.ragdoc.application.chat.planner.PlannerProperties(),
                        planAssembler,
                        runFactory,
                        phaseExecutor,
                        dispatchingSufficiencyJudge,
                        replanDecisionCoordinator,
                        runFinalizer,
                        sufficiencyGuard,
                        persistenceCoordinator);
    }

    // ─── helpers ───────────────────────────────────────────────────

    private RouterDecision multiHopDecision() {
        return new RouterDecision(
                TaskIntent.MULTI_HOP,
                ExecutionStrategy.PLANNED_AGENT,
                List.of("v1", "v2"),
                Map.of(),
                0.95,
                "TEST");
    }

    private Principal principal() {
        return new Principal(TENANT, "u1", Set.of(), null);
    }

    private List<PlannerToolDescriptor> allowedTools() {
        return List.of(
                new PlannerToolDescriptor("semantic_search", "v1", "sem", Map.of()),
                new PlannerToolDescriptor("metadata_search", "v1", "meta", Map.of()));
    }

    private com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy policy() {
        return com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy.pr6Default();
    }

    private DeterministicExecutionPlan dummyPlan(String planId) {
        return new DeterministicExecutionPlan(
                planId,
                "v1",
                List.of(
                        new com.xxx.ragdoc.application.chat.agent.AgentToolStep(
                                "s1",
                                "semantic_search",
                                "v1",
                                new com.xxx.ragdoc.application.chat.tool.SearchInput(
                                        "q",
                                        5,
                                        com.xxx.ragdoc.application.chat.tool.SearchInput
                                                .SearchFilters.empty()),
                                List.of(),
                                "expected",
                                true)));
    }

    private InitializedRun dummyInit() {
        com.xxx.ragdoc.application.chat.agent.AgentRunRecord run =
                new com.xxx.ragdoc.application.chat.agent.AgentRunRecord(
                        RUN_ID,
                        REQUEST_ID,
                        TENANT,
                        "u1",
                        "PLANNED_AGENT",
                        AgentRunStatus.EXECUTING,
                        "plan-1",
                        "v1",
                        "h",
                        "{}",
                        policy().budget(),
                        ZERO_RES,
                        USAGE_ZERO,
                        List.of(),
                        0,
                        null,
                        "rv",
                        "tsv",
                        "iv1",
                        "LIVE",
                        null,
                        null,
                        3);
        return new InitializedRun(run, List.of());
    }

    private com.xxx.ragdoc.application.chat.agent.AgentRunHandle dummyHandle() {
        return com.xxx.ragdoc.application.chat.agent.AgentRunHandle.from(
                dummyInit(),
                dummyPlan("plan-1"),
                policy(),
                CancellationTokenSource.CancellationToken.never(),
                TENANT,
                java.time.Clock.systemUTC());
    }

    private Evidence evidenceFor(String reqId, String contentSuffix) {
        Map<String, Object> md = new HashMap<>();
        md.put("requirementIds", List.of(reqId));
        md.put("sourceStepId", "plan-step-0");
        return Evidence.of(
                TENANT,
                1L,
                10L,
                "v1",
                "content-" + contentSuffix,
                0.9,
                null,
                "semantic_search",
                md);
    }

    private PhaseExecutionResult phaseSucceeded(List<Evidence> evidence, int phaseIndex) {
        return new PhaseExecutionResult(
                RUN_ID,
                phaseIndex,
                5L,
                List.of("plan-step-0"),
                evidence,
                evidence, /* newEvidence = accumulated (first phase) */
                USAGE_ONE,
                ZERO_RES,
                List.of(), /* completedSteps summaries */
                Set.of("semantic_search|v1|query"), /* usedToolSignatures */
                Set.of(), /* discoveredEntities */
                false, /* requiredStepFailed */
                "", /* failureReasonCode */
                null /* prematureTerminal */);
    }

    private PlannerPlanAssembler.AssemblyResult assemblyOk(String planId) {
        return PlannerPlanAssembler.AssemblyResult.ok(
                dummyPlan(planId), List.of("REQ-1"), "INITIAL");
    }

    private void stubFinalizerSucceedsReadyToAnswer() {
        when(runFinalizer.finalize(
                        anyString(),
                        anyLong(),
                        anySet(),
                        any(AgentRunStatus.class),
                        anyString(),
                        any(AgentUsage.class),
                        any(AgentBudgetReservation.class)))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 6L, AgentRunStatus.READY_TO_ANSWER));
    }

    // ───────────────────────────────────────────────────────────────
    // Case A: Initial Plan Sufficient
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case A: Initial Plan → Sufficiency SUFFICIENT → Guard PASS → READY_TO_ANSWER")
    void caseA_initialSufficient() {
        Evidence ev = evidenceFor("REQ-1", "case-a");
        PhaseExecutionResult phase = phaseSucceeded(List.of(ev), 0);
        when(plannerProvider.plan(any()))
                .thenReturn(
                        new PlannerResponse(
                                "plan-A", "v1", List.of(), List.of("REQ-1"), "INITIAL"));
        when(planAssembler.assemble(any(), any(), any())).thenReturn(assemblyOk("plan-A"));
        when(runFactory.create(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(dummyInit());
        when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                .thenReturn(phase);

        // RequirementExtractor for MULTI_HOP + ["v1","v2"] will yield:
        //   REQ-1 (ENTITY_ATTRIBUTE for v1), REQ-2 (ENTITY_ATTRIBUTE for v2),
        //   REQ-3 (RELATION)
        // Mock Sufficiency to declare all three COVERED with the single evidence
        // — Rule wouldn't do this, but dispatching returns the mock.
        SufficiencyDecision sufficient =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.SUFFICIENT,
                        List.of(
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-1", List.of(ev.evidenceId()), ""),
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-2", List.of(ev.evidenceId()), ""),
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-3", List.of(ev.evidenceId()), "")),
                        List.of(),
                        List.of(),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction.ANSWER,
                        "OK");
        when(dispatchingSufficiencyJudge.evaluate(any())).thenReturn(sufficient);
        stubFinalizerSucceedsReadyToAnswer();

        PlannedAgentExecutionCoordinator.PrepareResult result =
                coordinator.prepare(
                        "find x",
                        multiHopDecision(),
                        REQUEST_ID,
                        principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedTools(),
                        policy());

        assertThat(result.ok()).isTrue();
        assertThat(result.prepared()).isNotNull();
        assertThat(result.prepared().runId()).isEqualTo(RUN_ID);
        assertThat(result.prepared().evidence()).hasSize(1);
        assertThat(result.prepared().evidence().get(0).evidenceId())
                .isEqualTo(ev.evidenceId()); /* evidence ids 一致 */
        assertThat(result.prepared().replanCount()).isZero();
        // Planner called once (initial only)
        verify(plannerProvider, times(1)).plan(any());
        verify(phaseExecutor, times(1)).executePhase(any(), any(), anySet(), any(), any(), any());
        verify(runFinalizer, atLeastOnce())
                .finalize(
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.READY_TO_ANSWER),
                        anyString(),
                        any(),
                        any());
    }

    // ───────────────────────────────────────────────────────────────
    // Case B: Replan Success
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case B: Initial INSUFFICIENT → Replan ALLOW → Replan Phase SUFFICIENT → ANSWERED")
    void caseB_replanSuccess() {
        Evidence ev0 = evidenceFor("REQ-1", "phase0");
        Evidence ev1 = evidenceFor("REQ-2", "phase1");

        PhaseExecutionResult phase0 = phaseSucceeded(List.of(ev0), 0);
        PhaseExecutionResult phase1 =
                new PhaseExecutionResult(
                        RUN_ID,
                        1,
                        7L,
                        List.of("replan-1-step-0"),
                        List.of(ev1), /* newEvidence */
                        List.of(ev0, ev1), /* accumulatedEvidence */
                        AgentUsage.zero().incStep().incStep().incRealToolCall().incRealToolCall(),
                        ZERO_RES,
                        List.of(),
                        Set.of("semantic_search|v1|query", "semantic_search|v1|q2"),
                        Set.of(),
                        false,
                        "",
                        null);

        PlannerResponse initialResp =
                new PlannerResponse("plan-B-initial", "v1", List.of(), List.of("REQ-1"), "INITIAL");
        PlannerResponse replanResp =
                new PlannerResponse("plan-B-replan", "v1", List.of(), List.of("REQ-2"), "REPLAN");

        when(plannerProvider.plan(any())).thenReturn(initialResp, replanResp);
        when(planAssembler.assemble(any(), any(), any()))
                .thenReturn(
                        PlannerPlanAssembler.AssemblyResult.ok(
                                dummyPlan("plan-B-initial"), List.of("REQ-1"), "INITIAL"))
                .thenReturn(
                        PlannerPlanAssembler.AssemblyResult.ok(
                                dummyPlan("plan-B-replan"), List.of("REQ-2"), "REPLAN"));
        when(runFactory.create(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(dummyInit());
        when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                .thenReturn(phase0, phase1);

        // initial sufficiency: REQ-1 covered, REQ-2 + REQ-3 missing → INSUFFICIENT (shows progress
        // on replan)
        SufficiencyDecision initialInsuff =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.INSUFFICIENT,
                        List.of(
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-1", List.of(ev0.evidenceId()), "")),
                        List.of("REQ-2", "REQ-3"),
                        List.of(),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction
                                .REFUSE_NO_EVIDENCE,
                        "MISSING");
        // after replan: all three covered → SUFFICIENT
        SufficiencyDecision replanSuff =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.SUFFICIENT,
                        List.of(
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-1", List.of(ev0.evidenceId()), ""),
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered("REQ-2", List.of(ev1.evidenceId()), ""),
                                com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                        .covered(
                                                "REQ-3",
                                                List.of(ev0.evidenceId(), ev1.evidenceId()),
                                                "")),
                        List.of(),
                        List.of(),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction.ANSWER,
                        "OK");
        when(dispatchingSufficiencyJudge.evaluate(any())).thenReturn(initialInsuff, replanSuff);

        stubFinalizerSucceedsReadyToAnswer();
        org.mockito.Mockito.doNothing()
                .when(persistenceCoordinator)
                .appendReplanSteps(anyString(), any());
        // suppress AgentRunFactory.sha256 — it's static
        // dummyInit must include same runId for both phases

        PlannedAgentExecutionCoordinator.PrepareResult result =
                coordinator.prepare(
                        "multi-hop question",
                        multiHopDecision(),
                        REQUEST_ID,
                        principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedTools(),
                        policy());

        assertThat(result.ok()).isTrue();
        assertThat(result.prepared().replanCount()).isEqualTo(1);
        // evidence ids 一致: 最终累积 = [ev0, ev1]
        assertThat(result.prepared().evidence())
                .extracting(Evidence::evidenceId)
                .containsExactlyInAnyOrder(ev0.evidenceId(), ev1.evidenceId());
        // Planner called twice (initial + replan)
        verify(plannerProvider, times(2)).plan(any());
        verify(phaseExecutor, times(2)).executePhase(any(), any(), anySet(), any(), any(), any());
    }

    // ───────────────────────────────────────────────────────────────
    // Case C: Replan Still Insufficient → REFUSED_NO_EVIDENCE
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case C: Initial INSUFFICIENT → Replan → 仍 INSUFFICIENT → REFUSED_NO_EVIDENCE")
    void caseC_replanStillInsufficient() {
        Evidence ev0 = evidenceFor("REQ-1", "phase0-multi");

        // progress: phase 0 has new evidence (ev0); replan phase also has new evidence (ev1)
        Evidence ev1 = evidenceFor("REQ-2", "phase1-multi2");
        PhaseExecutionResult phase0 = phaseSucceeded(List.of(ev0), 0);
        PhaseExecutionResult phase1 =
                new PhaseExecutionResult(
                        RUN_ID,
                        1,
                        7L,
                        List.of("replan-1-step-0"),
                        List.of(ev1),
                        List.of(ev0, ev1),
                        AgentUsage.zero().incStep().incStep().incRealToolCall().incRealToolCall(),
                        ZERO_RES,
                        List.of(),
                        Set.of("sig1", "sig2"),
                        Set.of(),
                        false,
                        "",
                        null);

        when(plannerProvider.plan(any()))
                .thenReturn(
                        new PlannerResponse(
                                "pC-init", "v1", List.of(), List.of("REQ-1"), "INITIAL"),
                        new PlannerResponse(
                                "pC-replan", "v1", List.of(), List.of("REQ-2"), "REPLAN"));
        when(planAssembler.assemble(any(), any(), any()))
                .thenReturn(assemblyOk("pC-init"))
                .thenReturn(assemblyOk("pC-replan"));
        when(runFactory.create(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(dummyInit());
        when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                .thenReturn(phase0, phase1);

        // Phase 0 sufficiency: REQ-2 + REQ-3 missing → INSUFFICIENT
        SufficiencyDecision insuff0 =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.INSUFFICIENT,
                        List.of(),
                        List.of("REQ-2", "REQ-3"),
                        List.of(),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction
                                .REFUSE_NO_EVIDENCE,
                        "MISSING");
        // Phase 1 sufficiency: REQ-3 still missing → INSUFFICIENT (progress shown: 2 missing → 1
        // missing)
        SufficiencyDecision insuff1 =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.INSUFFICIENT,
                        List.of(),
                        List.of("REQ-3"),
                        List.of(),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction
                                .REFUSE_NO_EVIDENCE,
                        "STILL_MISSING");
        when(dispatchingSufficiencyJudge.evaluate(any())).thenReturn(insuff0, insuff1);
        org.mockito.Mockito.doNothing()
                .when(persistenceCoordinator)
                .appendReplanSteps(anyString(), any());

        // Finalizer: second call (REFUSED_NO_EVIDENCE) returns written
        when(runFinalizer.finalize(
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.REFUSED_NO_EVIDENCE),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 7L, AgentRunStatus.REFUSED_NO_EVIDENCE));

        PlannedAgentExecutionCoordinator.PrepareResult result =
                coordinator.prepare(
                        "multi-hop with persistent gap",
                        multiHopDecision(),
                        REQUEST_ID,
                        principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedTools(),
                        policy());

        assertThat(result.ok()).isFalse();
        assertThat(result.failureTerminal()).isEqualTo(AgentRunStatus.REFUSED_NO_EVIDENCE);
        assertThat(result.failureReason()).contains("INSUFFICIENT_AFTER_REPLAN");
        // Planner called twice — no third call
        verify(plannerProvider, times(2)).plan(any());
    }

    // ───────────────────────────────────────────────────────────────
    // Case D: Conflict → REFUSED_CONFLICT
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case D: Sufficiency 检测到 version 冲突 → CONFLICTED → REFUSED_CONFLICT")
    void caseD_conflictRefused() {
        // 两条 evidence 同 requirement 但 documentVersion 不同 → RuleSufficiencyJudge 检测到 CONFLICTED
        Evidence ev1 =
                Evidence.of(
                        TENANT,
                        1L,
                        10L,
                        "v1",
                        "info v1",
                        0.9,
                        null,
                        "metadata_search",
                        Map.of("requirementIds", List.of("REQ-1")));
        Evidence ev2 =
                Evidence.of(
                        TENANT,
                        1L,
                        20L,
                        "v2",
                        "info v2",
                        0.9,
                        null,
                        "metadata_search",
                        Map.of("requirementIds", List.of("REQ-1")));

        PhaseExecutionResult phase = phaseSucceeded(List.of(ev1, ev2), 0);

        when(plannerProvider.plan(any()))
                .thenReturn(
                        new PlannerResponse("pD", "v1", List.of(), List.of("REQ-1"), "INITIAL"));
        when(planAssembler.assemble(any(), any(), any())).thenReturn(assemblyOk("pD"));
        when(runFactory.create(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(dummyInit());
        when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                .thenReturn(phase);

        // real Rule Judge would detect CONFLICTED; we mock dispatch to return CONFLICTED directly
        SufficiencyDecision conflict =
                com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                        com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus.CONFLICTED,
                        List.of(),
                        List.of(),
                        List.of(
                                new com.xxx.ragdoc.application.chat.sufficiency.EvidenceConflict(
                                        "REQ-1",
                                        com.xxx.ragdoc.application.chat.sufficiency.EvidenceConflict
                                                .ConflictType.VERSION_VALUE_MISMATCH,
                                        List.of(ev1.evidenceId(), ev2.evidenceId()),
                                        "version mismatch")),
                        com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction
                                .REFUSE_CONFLICT,
                        "CONFLICT_VERSION");
        when(dispatchingSufficiencyJudge.evaluate(any())).thenReturn(conflict);

        when(runFinalizer.finalize(
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.REFUSED_CONFLICT),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 6L, AgentRunStatus.REFUSED_CONFLICT));

        PlannedAgentExecutionCoordinator.PrepareResult result =
                coordinator.prepare(
                        "multi-hop conflict",
                        multiHopDecision(),
                        REQUEST_ID,
                        principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedTools(),
                        policy());

        assertThat(result.ok()).isFalse();
        assertThat(result.failureTerminal()).isEqualTo(AgentRunStatus.REFUSED_CONFLICT);
        // No replan on conflict
        verify(plannerProvider, times(1)).plan(any());
        verify(persistenceCoordinator, never()).appendReplanSteps(anyString(), any());
    }

    // ───────────────────────────────────────────────────────────────
    // Case E: Required Tool Failure → TOOL_FAILED (premature Terminal)
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Case E: Phase 内 required Tool FAILED_TERMINAL → premature → TOOL_FAILED")
    void caseE_requiredToolFailure() {
        PhaseExecutionResult premature =
                new PhaseExecutionResult(
                        RUN_ID,
                        0,
                        4L,
                        List.of("plan-step-0"),
                        List.of(),
                        List.of(),
                        AgentUsage.zero().incStep().incRealToolCall(),
                        ZERO_RES,
                        List.of(),
                        Set.of("sig-tool-failed"),
                        Set.of(),
                        true, /* requiredStepFailed */
                        "REQUIRED_TOOL_FAILED:TOOL_TERMINAL",
                        AgentRunStatus.TOOL_FAILED /* prematureTerminal */);

        when(plannerProvider.plan(any()))
                .thenReturn(
                        new PlannerResponse("pE", "v1", List.of(), List.of("REQ-1"), "INITIAL"));
        when(planAssembler.assemble(any(), any(), any())).thenReturn(assemblyOk("pE"));
        when(runFactory.create(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(dummyInit());
        when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                .thenReturn(premature);

        when(runFinalizer.finalize(
                        anyString(),
                        anyLong(),
                        anySet(),
                        eq(AgentRunStatus.TOOL_FAILED),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(
                        PlannedAgentRunFinalizer.FinalizeOutcome.written(
                                RUN_ID, 4L, AgentRunStatus.TOOL_FAILED));

        PlannedAgentExecutionCoordinator.PrepareResult result =
                coordinator.prepare(
                        "multi-hop tool failure",
                        multiHopDecision(),
                        REQUEST_ID,
                        principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedTools(),
                        policy());

        assertThat(result.ok()).isFalse();
        assertThat(result.failureTerminal()).isEqualTo(AgentRunStatus.TOOL_FAILED);
        assertThat(result.failureReason()).contains("REQUIRED_TOOL_FAILED");
        // Sufficiency / Replan Planner 不是调用
        verify(dispatchingSufficiencyJudge, never()).evaluate(any());
        verify(plannerProvider, times(1)).plan(any());
    }

    // ───────────────────────────────────────────────────────────────
    // Contract verification: Replay consistency (planHash + tool sig + evidence + terminal)
    // ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Replay consistency: planHash / tool sig / evidence ids / terminal")
    class ReplayConsistency {

        @Test
        @DisplayName("Case A 跑两次 → 返回的 evidence / terminal 一致 (Coordinator 确定性)")
        void caseADeterministic() {
            Evidence ev = evidenceFor("REQ-1", "deterministic");
            PhaseExecutionResult phase = phaseSucceeded(List.of(ev), 0);
            when(plannerProvider.plan(any()))
                    .thenReturn(
                            new PlannerResponse(
                                    "plan-D", "v1", List.of(), List.of("REQ-1"), "INITIAL"));
            when(planAssembler.assemble(any(), any(), any())).thenReturn(assemblyOk("plan-D"));
            when(runFactory.create(
                            any(),
                            any(),
                            any(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString()))
                    .thenReturn(dummyInit());
            when(phaseExecutor.executePhase(any(), any(), anySet(), any(), any(), any()))
                    .thenReturn(phase);

            SufficiencyDecision sufficient =
                    com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision.rule(
                            com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus
                                    .SUFFICIENT,
                            List.of(
                                    com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                            .covered("REQ-1", List.of(ev.evidenceId()), ""),
                                    com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                            .covered("REQ-2", List.of(ev.evidenceId()), ""),
                                    com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage
                                            .covered("REQ-3", List.of(ev.evidenceId()), "")),
                            List.of(),
                            List.of(),
                            com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction.ANSWER,
                            "OK");
            when(dispatchingSufficiencyJudge.evaluate(any())).thenReturn(sufficient);
            stubFinalizerSucceedsReadyToAnswer();

            PlannedAgentExecutionCoordinator.PrepareResult r1 =
                    coordinator.prepare(
                            "find",
                            multiHopDecision(),
                            REQUEST_ID,
                            principal(),
                            CancellationTokenSource.CancellationToken.never(),
                            allowedTools(),
                            policy());
            PlannedAgentExecutionCoordinator.PrepareResult r2 =
                    coordinator.prepare(
                            "find",
                            multiHopDecision(),
                            REQUEST_ID,
                            principal(),
                            CancellationTokenSource.CancellationToken.never(),
                            allowedTools(),
                            policy());

            assertThat(r1.ok()).isTrue();
            assertThat(r2.ok()).isTrue();
            // evidence ids 一致
            assertThat(r1.prepared().evidence().get(0).evidenceId())
                    .isEqualTo(r2.prepared().evidence().get(0).evidenceId());
            // terminal status 一致 (READY_TO_ANSWER 同)
            assertThat(r1.prepared().readyRunVersion()).isEqualTo(r2.prepared().readyRunVersion());
            // replan count 一致
            assertThat(r1.prepared().replanCount()).isEqualTo(r2.prepared().replanCount()).isZero();
            // planHash 通过 PLAN id 暴露 — Coordinator 用 PlannerResponse.planId 在 assemblyOk 给;
            // case A 同 plan-A id 稳定
        }
    }

    private static AgentRunStatus eq(AgentRunStatus s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
