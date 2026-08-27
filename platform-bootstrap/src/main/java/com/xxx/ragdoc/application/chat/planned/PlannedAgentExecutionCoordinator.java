package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentRunFactory;
import com.xxx.ragdoc.application.chat.agent.AgentRunHandle;
import com.xxx.ragdoc.application.chat.agent.AgentRunPhaseExecutor;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentToolStep;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.PhaseExecutionContext;
import com.xxx.ragdoc.application.chat.agent.PhaseExecutionResult;
import com.xxx.ragdoc.application.chat.agent.ReplanDecisionCoordinator;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.AgentBudgetView;
import com.xxx.ragdoc.application.chat.planner.CompletedStepSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.PlannerPlanAssembler;
import com.xxx.ragdoc.application.chat.planner.PlannerProvider;
import com.xxx.ragdoc.application.chat.planner.PlannerRequest;
import com.xxx.ragdoc.application.chat.planner.PlannerResponse;
import com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.chat.sufficiency.DispatchingSufficiencyJudge;
import com.xxx.ragdoc.application.chat.sufficiency.RecommendedAction;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyDecision;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyRequest;
import com.xxx.ragdoc.application.chat.sufficiency.SufficiencyStatus;
import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7c.3c / EMS-PR7 §4: 同步路径主体 — Requirement → Initial Planner → Phase → Sufficiency → (最多一次)
 * Replan → Phase → Sufficiency → Guard → READY_TO_ANSWER。
 *
 * <p>不调用 Answer Composer / Citation / Finalizer ANSWERED — Pipeline 薄适配层后置。
 *
 * <p>不变量 (Revision §2 §5 §6 + §13 退出门禁):
 *
 * <ul>
 *   <li>同一 Run 跨 Initial + Replan (不创建第二个 agent_run)
 *   <li>Budget / usage / reservation / deadline / accumulator 跨 Phase 续作 (不重置)
 *   <li>Phase 完成后 Run 保持 EXECUTING (KEEP_EXECUTING)
 *   <li>SufficiencyDecisionGuard 通过 + CAS 才进 READY_TO_ANSWER
 *   <li>prematureTerminal (cancel/timeout/budget/permission/tool failure/conflict) 直接转 Finalizer 目标
 *   <li>最多一次 Replan — 第二次 Replan 阻断
 *   <li>Loop Detection: 历史 Tool Signature 集单调增长; 重复 → PLAN_REPEATED_TOOL_CALL
 *   <li>No-progress → REFUSED_NO_EVIDENCE reasonCode=AGENT_NO_PROGRESS
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannedAgentExecutionCoordinator {

    private final RuleTemplateRequirementExtractor requirementExtractor;
    private final PlannerProvider plannerProvider;
    private final com.xxx.ragdoc.application.chat.planner.PlannerProperties plannerProperties;
    private final PlannerPlanAssembler planAssembler;
    private final AgentRunFactory runFactory;
    private final AgentRunPhaseExecutor phaseExecutor;
    private final DispatchingSufficiencyJudge sufficiencyJudge;
    private final ReplanDecisionCoordinator replanDecisionCoordinator;
    private final PlannedAgentRunFinalizer runFinalizer;
    private final SufficiencyDecisionGuard sufficiencyGuard;
    private final com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator
            persistenceCoordinator;
    private final Clock clock = Clock.systemUTC();

    public PrepareResult prepare(
            String normalizedQuery,
            RouterDecision routerDecision,
            String requestId,
            Principal principal,
            CancellationTokenSource.CancellationToken cancellation,
            List<PlannerToolDescriptor> allowedTools,
            AgentExecutionPolicy policy) {
        // 1. Requirement Extraction
        RuleTemplateRequirementExtractor.RequirementExtractionResult reqEx =
                requirementExtractor.extract(routerDecision, normalizedQuery);
        if (!reqEx.valid()) {
            return PrepareResult.structuralFailure(
                    "REQUIREMENT_EXTRACTION_FAILED:" + reqEx.invalidReason());
        }
        List<EvidenceRequirement> frozenRequirements = reqEx.requirements();

        // 2. Initial Planner + 整定
        AgentBudgetView initialBudgetView = toBudgetView(policy.budget(), AgentUsage.zero());
        PlannerRequest plannerReq0 =
                new PlannerRequest(
                        /* runId 占位 */ "pending",
                        normalizedQuery,
                        routerDecision != null ? routerDecision.intent() : TaskIntent.MULTI_HOP,
                        routerDecision != null ? routerDecision.entities() : List.of(),
                        routerDecision != null ? routerDecision.filters() : Map.of(),
                        frozenRequirements,
                        com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary.empty(),
                        List.of(),
                        initialBudgetView,
                        allowedTools,
                        0 /* replanIndex */);
        PlannerResponse plannerResp0;
        try {
            plannerResp0 = plannerProvider.plan(plannerReq0);
        } catch (RuntimeException ex) {
            log.warn("planned.initial_planner_failed req={} err={}", requestId, ex.toString());
            return PrepareResult.structuralFailure("INITIAL_PLANNER_FAILED");
        }
        PlannerPlanAssembler.AssemblyResult asm0 =
                planAssembler.assemble(plannerReq0, plannerResp0, policy);
        if (!asm0.valid()) {
            return PrepareResult.structuralFailure("INVALID_INITIAL_PLAN:" + asm0.invalidReason());
        }
        DeterministicExecutionPlan phase0Plan = asm0.plan();

        // 3. 创建 Run
        InitializedRun init;
        try {
            init =
                    runFactory.create(
                            phase0Plan,
                            policy,
                            principal,
                            requestId,
                            "PLANNED_AGENT",
                            resolvePlannerVersionTag(plannerResp0),
                            "toolset-v1",
                            "default",
                            "LIVE");
        } catch (RuntimeException ex) {
            log.warn("planned.run_factory_failed req={} err={}", requestId, ex.toString());
            return PrepareResult.structuralFailure("RUN_INITIALIZATION_FAILED");
        }

        // 4. Initial Phase
        AgentRunHandle handle =
                AgentRunHandle.from(
                        init, phase0Plan, policy, cancellation, principal.tenantId(), clock);
        Map<String, String> reqIdToStepId = mapReqIdToStepId(asm0, phase0Plan);
        PhaseExecutionResult phase0 =
                phaseExecutor.executePhase(
                        handle,
                        phase0Plan.steps(),
                        Set.of(),
                        reqIdToStepId,
                        PhaseExecutionContext.initial(init.run().version(), Instant.now(clock)),
                        cancellation);
        // Phase premature terminal
        if (phase0.prematureTerminal() != null) {
            finalizePremature(
                    phase0.runId(),
                    phase0.latestRunVersion(),
                    phase0.prematureTerminal(),
                    phase0.failureReasonCode(),
                    phase0.usage(),
                    phase0.reservation());
            return PrepareResult.prematureFailure(
                    phase0.runId(), phase0.prematureTerminal(), phase0.failureReasonCode());
        }

        // 5. Initial Sufficiency
        SufficiencyDecision suff0 = callSufficiency(phase0, frozenRequirements, policy, normalizedQuery);
        SufficiencyDecisionGuard.GuardResult guard0 =
                sufficiencyGuard.validateForAnswer(
                        suff0, frozenRequirements, phase0.accumulatedEvidence());

        if (guard0.allowed()) {
            // EXECUTING → READY_TO_ANSWER
            PlannedAgentRunFinalizer.FinalizeOutcome fo =
                    runFinalizer.finalize(
                            phase0.runId(),
                            phase0.latestRunVersion(),
                            Set.of(AgentRunStatus.EXECUTING),
                            AgentRunStatus.READY_TO_ANSWER,
                            "PLANNED_INITIAL_SUFFICIENT",
                            phase0.usage(),
                            phase0.reservation());
            return buildPrepared(
                    phase0,
                    suff0,
                    frozenRequirements,
                    fo.newVersion(),
                    0 /* replanCount */,
                    cancellation);
        }

        // 6. CONFLICTED?
        if (suff0.status() == SufficiencyStatus.CONFLICTED
                || suff0.action() == RecommendedAction.REFUSE_CONFLICT) {
            finalizePremature(
                    phase0.runId(),
                    phase0.latestRunVersion(),
                    AgentRunStatus.REFUSED_CONFLICT,
                    "CONFLICT",
                    phase0.usage(),
                    phase0.reservation());
            return PrepareResult.prematureFailure(
                    phase0.runId(), AgentRunStatus.REFUSED_CONFLICT, "CONFLICT");
        }

        // 7. Replan decision
        // Phase 0 的 "prior accumulated" 在 Phase 开始前是空 — progress 判定需要
        // phase0.newEvidence 来证明 Phase 0 有效产生新证据; 不能把 phase0 自己的 accumulated
        // 当 prior, 否则永远 NO_PROGRESS.
        ReplanDecisionCoordinator.ReplanDecision rd =
                replanDecisionCoordinator.decide(
                        phase0,
                        suff0,
                        new HashSet<>() /* empty prior accumulated — Phase 0 前无证据 */,
                        com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary
                                .empty() /* prior uncovered */,
                        0 /* replanCount */,
                        // P0-3: 从 policy 预算读(原字面量 1 与硬编码 pr6Default 的
                        // maxReplans=0 相互矛盾)
                        policy.budget().maxReplans(),
                        cancellation.isCancelled(),
                        policy.budget());
        if (!rd.allowed()) {
            // 直接转 rd 终态
            runFinalizer.finalize(
                    phase0.runId(),
                    phase0.latestRunVersion(),
                    Set.of(AgentRunStatus.EXECUTING),
                    rd.terminalStatusIfRefused(),
                    rd.reasonIfRefused(),
                    phase0.usage(),
                    phase0.reservation());
            return PrepareResult.prematureFailure(
                    phase0.runId(), rd.terminalStatusIfRefused(), rd.reasonIfRefused());
        }

        // 8. Replan (replanIndex=1)
        PrepareResult replanResult =
                runReplanPhase(
                        handle,
                        phase0,
                        suff0,
                        frozenRequirements,
                        normalizedQuery,
                        routerDecision,
                        allowedTools,
                        policy,
                        cancellation,
                        reqIdToStepId);
        return replanResult;
    }

    /**
     * P0-1/P0-2: agent_run 的 planner 版本标记按实际来源写 — 替换原硬编码 "rule-based-v1"。
     * 评测 runner 通过 /agent/runs/{id}.plannerVersion 逐样本判定 planner_source:
     * model-llm-v1[:retry] / rule-fallback-v1:REASON / rule-based-v1。
     */
    private String resolvePlannerVersionTag(
            com.xxx.ragdoc.application.chat.planner.PlannerResponse resp) {
        String reason = resp == null ? "" : resp.reasonCode() == null ? "" : resp.reasonCode();
        if (reason.startsWith(
                com.xxx.ragdoc.application.chat.planner.FallbackPlannerProvider
                        .REASON_RULE_FALLBACK)) {
            // "RULE_FALLBACK_AFTER_MODEL_FAILURE:TIMEOUT:att2" → "rule-fallback-v1:TIMEOUT"
            String[] parts = reason.split(":");
            return "rule-fallback-v1:" + (parts.length > 1 ? parts[1] : "UNKNOWN");
        }
        if (!plannerProperties.isModelEnabled()) {
            return "rule-based-v1";
        }
        if (reason.startsWith(
                com.xxx.ragdoc.application.chat.planner.FallbackPlannerProvider
                        .REASON_MODEL_RETRY)) {
            return "model-llm-v1:retry";
        }
        return "model-llm-v1";
    }

    private PrepareResult runReplanPhase(
            AgentRunHandle handle,
            PhaseExecutionResult phase0,
            SufficiencyDecision suff0,
            List<EvidenceRequirement> frozenRequirements,
            String normalizedQuery,
            RouterDecision routerDecision,
            List<PlannerToolDescriptor> allowedTools,
            AgentExecutionPolicy policy,
            CancellationTokenSource.CancellationToken cancellation,
            Map<String, String> initialReqIdToStepId) {
        // build replan planner request
        AgentBudgetView replanBudgetView = toBudgetView(policy.budget(), phase0.usage());
        PlannerRequest replanReq =
                new PlannerRequest(
                        phase0.runId(),
                        normalizedQuery,
                        TaskIntent.MULTI_HOP,
                        routerDecision != null ? routerDecision.entities() : List.of(),
                        routerDecision != null ? routerDecision.filters() : Map.of(),
                        frozenRequirements,
                        coverageFromDecision(suff0),
                        phase0.completedSteps(),
                        replanBudgetView,
                        allowedTools,
                        1 /* replanIndex */);
        PlannerResponse replanResp;
        try {
            replanResp = plannerProvider.plan(replanReq);
        } catch (RuntimeException ex) {
            log.warn("planned.replan_planner_failed run={} err={}", phase0.runId(), ex.toString());
            return replanFailureFallback(
                    phase0, frozenRequirements, cancellation, "REPLAN_PLANNER_FAILED");
        }

        PlannerPlanAssembler.AssemblyResult asm =
                planAssembler.assemble(replanReq, replanResp, policy);
        if (!asm.valid()) {
            return replanFailureFallback(
                    phase0, frozenRequirements, cancellation,
                    "REPLAN_INVALID:" + asm.invalidReason());
        }
        DeterministicExecutionPlan replanPlan = asm.plan();

        // PR-7c.3c-2: 在同一 Run 内原子追加 Replan Steps (单事务)
        int nextSeq = phase0.completedSteps().size(); // 续 max sequence + 1
        java.util.List<com.xxx.ragdoc.application.chat.agent.AgentStepRecord> replanSteps =
                new java.util.ArrayList<>();
        int seqCursor = nextSeq;
        for (AgentToolStep s : replanPlan.steps()) {
            replanSteps.add(
                    new com.xxx.ragdoc.application.chat.agent.AgentStepRecord(
                            phase0.runId(),
                            s.stepId(),
                            seqCursor,
                            s.toolName(),
                            s.toolVersion(),
                            null,
                            com.xxx.ragdoc.application.chat.agent.AgentRunFactory.sha256(
                                    s.input() == null ? "" : s.input().normalizedForDedup()),
                            com.xxx.ragdoc.application.chat.agent.AgentStepStatus.PENDING,
                            0,
                            java.util.List.of(),
                            null,
                            null,
                            false,
                            false,
                            false,
                            null,
                            null,
                            java.time.Instant.now(clock),
                            java.time.Instant.now(clock),
                            0));
            seqCursor++;
        }
        try {
            persistenceCoordinatorAppend(phase0.runId(), replanSteps);
        } catch (RuntimeException ex) {
            log.warn("planned.replan_append_failed run={} err={}", phase0.runId(), ex.toString());
            runFinalizer.finalize(
                    phase0.runId(),
                    phase0.latestRunVersion(),
                    java.util.Set.of(
                            com.xxx.ragdoc.application.chat.agent.AgentRunStatus.EXECUTING),
                    com.xxx.ragdoc.application.chat.agent.AgentRunStatus.SYSTEM_FAILED,
                    "REPLAN_APPEND_FAILED",
                    phase0.usage(),
                    phase0.reservation());
            return PrepareResult.prematureFailure(
                    phase0.runId(),
                    com.xxx.ragdoc.application.chat.agent.AgentRunStatus.SYSTEM_FAILED,
                    "REPLAN_APPEND_FAILED");
        }

        Map<String, String> replanReqMap = mapReqIdToStepId(asm, replanPlan);
        PhaseExecutionContext phase1Ctx =
                new PhaseExecutionContext(
                        1,
                        phase0.usage(),
                        phase0.reservation(),
                        phase0.accumulatedEvidence(),
                        phase0.accumulatedEvidence().stream().map(Evidence::evidenceId).toList(),
                        phase0.completedSteps(),
                        phase0.usedToolSignatures(),
                        countExecuted(phase0),
                        phase0.latestRunVersion(),
                        Instant.now(clock),
                        replanPlan.planId(),
                        replanPlan.planVersion());

        PhaseExecutionResult phase1;
        try {
            phase1 =
                    phaseExecutor.executePhase(
                            handle,
                            replanPlan.steps(),
                            Set.of(/* requirements 继承 */ ),
                            replanReqMap,
                            phase1Ctx,
                            cancellation);
        } catch (RuntimeException ex) {
            log.warn("planned.replan_phase_failed run={} err={}", phase0.runId(), ex.toString());
            return PrepareResult.prematureFailure(
                    phase0.runId(), AgentRunStatus.SYSTEM_FAILED, "REPLAN_PHASE_FAILED");
        }

        if (phase1.prematureTerminal() != null) {
            finalizePremature(
                    phase1.runId(),
                    phase1.latestRunVersion(),
                    phase1.prematureTerminal(),
                    phase1.failureReasonCode(),
                    phase1.usage(),
                    phase1.reservation());
            return PrepareResult.prematureFailure(
                    phase1.runId(), phase1.prematureTerminal(), phase1.failureReasonCode());
        }

        SufficiencyDecision suff1 = callSufficiency(phase1, frozenRequirements, policy, normalizedQuery);
        SufficiencyDecisionGuard.GuardResult guard1 =
                sufficiencyGuard.validateForAnswer(
                        suff1, frozenRequirements, phase1.accumulatedEvidence());

        if (guard1.allowed()) {
            PlannedAgentRunFinalizer.FinalizeOutcome fo =
                    runFinalizer.finalize(
                            phase1.runId(),
                            phase1.latestRunVersion(),
                            Set.of(AgentRunStatus.EXECUTING),
                            AgentRunStatus.READY_TO_ANSWER,
                            "PLANNED_REPLAN_SUFFICIENT",
                            phase1.usage(),
                            phase1.reservation());
            return buildPrepared(
                    phase1,
                    suff1,
                    frozenRequirements,
                    fo.newVersion(),
                    1 /* replanCount */,
                    cancellation);
        }
        // CONFLICTED → REFUSED_CONFLICT
        if (suff1.status() == SufficiencyStatus.CONFLICTED) {
            finalizePremature(
                    phase1.runId(),
                    phase1.latestRunVersion(),
                    AgentRunStatus.REFUSED_CONFLICT,
                    "CONFLICT",
                    phase1.usage(),
                    phase1.reservation());
            return PrepareResult.prematureFailure(
                    phase1.runId(), AgentRunStatus.REFUSED_CONFLICT, "CONFLICT");
        }
        // 改动(2026-08-25): INSUFFICIENT_AFTER_REPLAN 不再终态拒答 —
        // 65% 拒答率的终因。降级为 Classic-style 回答: 用已累积的证据直接 Composer 生成,
        // 标注 INSUFFICIENT_AFTER_REPLAN_FALLBACK。run 状态改为 READY_TO_ANSWER(带 reason)
        // 而非 REFUSED_NO_EVIDENCE, 只有完全无证据才保持拒答。
        String reason = "INSUFFICIENT_AFTER_REPLAN_FALLBACK";
        boolean hasAnyEvidence = !phase1.accumulatedEvidence().isEmpty();
        if (hasAnyEvidence) {
            PlannedAgentRunFinalizer.FinalizeOutcome fo =
                    runFinalizer.finalize(
                            phase1.runId(),
                            phase1.latestRunVersion(),
                            Set.of(AgentRunStatus.EXECUTING),
                            AgentRunStatus.READY_TO_ANSWER,
                            reason,
                            phase1.usage(),
                            phase1.reservation());
            SufficiencyDecision fallbackSuff =
                    SufficiencyDecision.rule(
                            SufficiencyStatus.PARTIAL,
                            List.of(),
                            List.of(),
                            List.of(),
                            RecommendedAction.ANSWER_PARTIAL,
                            reason);
            return buildPrepared(
                    phase1,
                    fallbackSuff,
                    frozenRequirements,
                    fo.newVersion(),
                    1 /* replanCount */,
                    cancellation);
        }
        // 完全无证据 → 保持拒答(防幻觉底线)
        runFinalizer.finalize(
                phase1.runId(),
                phase1.latestRunVersion(),
                Set.of(AgentRunStatus.EXECUTING),
                AgentRunStatus.REFUSED_NO_EVIDENCE,
                "INSUFFICIENT_AFTER_REPLAN_NO_EVIDENCE",
                phase1.usage(),
                phase1.reservation());
        return PrepareResult.prematureFailure(
                phase1.runId(),
                AgentRunStatus.REFUSED_NO_EVIDENCE,
                "INSUFFICIENT_AFTER_REPLAN_NO_EVIDENCE");
    }


    /**
     * 改动(2026-08-25): Replan 失败(REPLAN_INVALID / PLANNER_FAILED)时的降级处理。
     * 有任何证据 → PARTIAL 回答(防 65% 拒答); 无证据 → 保持拒答(防幻觉底线)。
     */
    private PrepareResult replanFailureFallback(
            PhaseExecutionResult phase,
            List<EvidenceRequirement> frozenRequirements,
            CancellationTokenSource.CancellationToken cancellation,
            String reason) {
        if (!phase.accumulatedEvidence().isEmpty()) {
            // 注意: finalize 会递增 version, 必须用 FinalizeOutcome.newVersion()
            // 而非 phase.latestRunVersion()(是 finalize 前的旧值, 会导致后续
            // ANSWERED CAS 必然 affected=0 → "已被取消或终止")
            PlannedAgentRunFinalizer.FinalizeOutcome fo =
                    runFinalizer.finalize(
                            phase.runId(),
                            phase.latestRunVersion(),
                            Set.of(AgentRunStatus.EXECUTING),
                            AgentRunStatus.READY_TO_ANSWER,
                            reason + "_FALLBACK",
                            phase.usage(),
                            phase.reservation());
            SufficiencyDecision fallbackSuff =
                    SufficiencyDecision.rule(
                            SufficiencyStatus.PARTIAL,
                            List.of(),
                            List.of(),
                            List.of(),
                            RecommendedAction.ANSWER_PARTIAL,
                            reason + "_FALLBACK");
            return buildPrepared(
                    phase,
                    fallbackSuff,
                    frozenRequirements,
                    fo.newVersion(),
                    1,
                    cancellation);
        }
        runFinalizer.finalize(
                phase.runId(),
                phase.latestRunVersion(),
                Set.of(AgentRunStatus.EXECUTING),
                AgentRunStatus.REFUSED_NO_EVIDENCE,
                reason + "_NO_EVIDENCE",
                phase.usage(),
                phase.reservation());
        return PrepareResult.prematureFailure(
                phase.runId(), AgentRunStatus.REFUSED_NO_EVIDENCE, reason + "_NO_EVIDENCE");
    }

    private SufficiencyDecision callSufficiency(
            PhaseExecutionResult phase,
            List<EvidenceRequirement> requirements,
            AgentExecutionPolicy policy,
            String normalizedQuery) {
        Set<String> completedRequired = new HashSet<>();
        for (CompletedStepSummary s : phase.completedSteps()) {
            // PR-7c.3c 简化: 标记 SUCCEEDED/EMPTY 为 completed required
            if ("SUCCEEDED".equals(s.outcome())) completedRequired.add(s.stepId());
        }
        SufficiencyRequest req =
                new SufficiencyRequest(
                        phase.runId(),
                        // P0修复: 传入原始查询——判定器需要知道问题才能判断"证据是否充分"
                        // (原传空串, 判定器盲判, 多跳题100%走LLM fallback)
                        normalizedQuery,
                        requirements,
                        phase.accumulatedEvidence(),
                        completedRequired,
                        phase.usedToolSignatures(),
                        com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary.empty(),
                        0 /* replanIndex via phase index: phase.phaseIndex() */,
                        false,
                        Map.of());
        return sufficiencyJudge.evaluate(req);
    }

    private PrepareResult buildPrepared(
            PhaseExecutionResult phase,
            SufficiencyDecision suff,
            List<EvidenceRequirement> requirements,
            long newRunVersion,
            int replanCount,
            CancellationTokenSource.CancellationToken cancellation) {
        return PrepareResult.ok(
                new PreparedGroundedAnswer(
                        phase.runId(), /* requestId */
                        "",
                        "",
                        requirements,
                        suff.coverage(),
                        phase.accumulatedEvidence(),
                        phase.usage(),
                        phase.reservation(),
                        replanCount,
                        Instant.now(clock),
                        cancellation,
                        newRunVersion));
    }

    private void finalizePremature(
            String runId,
            long runVersion,
            AgentRunStatus target,
            String reasonCode,
            AgentUsage usage,
            AgentBudgetReservation reservation) {
        runFinalizer.finalize(
                runId,
                runVersion,
                Set.of(AgentRunStatus.EXECUTING),
                target,
                reasonCode,
                usage,
                reservation);
    }

    private void persistenceCoordinatorAppend(
            String runId,
            java.util.List<com.xxx.ragdoc.application.chat.agent.AgentStepRecord> steps) {
        persistenceCoordinator.appendReplanSteps(runId, steps);
    }

    private static Set<String> priorIds(List<Evidence> evs) {
        Set<String> out = new HashSet<>();
        for (Evidence e : evs) out.add(e.evidenceId());
        return out;
    }

    private static int countExecuted(PhaseExecutionResult p) {
        return p.executedStepIds().size();
    }

    private static AgentBudgetView toBudgetView(AgentBudget budget, AgentUsage usage) {
        return new AgentBudgetView(
                budget.maxSteps() - usage.usedSteps(),
                budget.maxToolCalls() - usage.usedToolCalls(),
                budget.maxSteps(),
                budget.maxToolCalls(),
                budget.maxExecutionMillis(),
                1);
    }

    private static com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary
            coverageFromDecision(SufficiencyDecision d) {
        List<String> covered = new ArrayList<>();
        List<String> missing = new ArrayList<>(d.missingRequirementIds());
        for (var cov : d.coverage()) {
            if (cov.status()
                    == com.xxx.ragdoc.application.chat.sufficiency.CoverageStatus.COVERED) {
                covered.add(cov.requirementId());
            }
        }
        return new com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary(
                covered.size(),
                List.copyOf(covered),
                List.of(),
                List.copyOf(missing),
                List.of(),
                Map.of());
    }

    /**
     * P0-2 修复: reqId → stepId 映射真实现(此前恒空 Map → Rule SufficiencyJudge 对所有
     * requirement 恒 NO_EVIDENCE → Planned run 必然拒答)。从 plan steps 的
     * requirementIds(Assembler 已透传)反转构建; 一个 reqId 被多 step 服务时取首个
     * (PhaseExecutor 注入证据归属只需任一来源)。
     */
    private static Map<String, String> mapReqIdToStepId(
            PlannerPlanAssembler.AssemblyResult asm, DeterministicExecutionPlan plan) {
        Map<String, String> out = new HashMap<>();
        for (var step : plan.steps()) {
            if (step.requirementIds() == null || step.requirementIds().isEmpty()) continue;
            for (String rid : step.requirementIds()) {
                out.putIfAbsent(rid, step.stepId());
            }
        }
        return out;
    }

    // ─── Result types ────────────────────────────────────

    public record PrepareResult(
            boolean ok,
            String failureRunId,
            AgentRunStatus failureTerminal,
            String failureReason,
            PreparedGroundedAnswer prepared) {

        public static PrepareResult ok(PreparedGroundedAnswer p) {
            return new PrepareResult(true, null, null, null, p);
        }

        public static PrepareResult prematureFailure(
                String runId, AgentRunStatus terminal, String reason) {
            return new PrepareResult(false, runId, terminal, reason, null);
        }

        public static PrepareResult structuralFailure(String reason) {
            return new PrepareResult(false, null, null, reason, null);
        }
    }

    public record PreparedGroundedAnswer(
            String runId,
            String requestId,
            String originalQuery,
            List<EvidenceRequirement> requirements,
            List<com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage> coverage,
            List<Evidence> evidence,
            AgentUsage usage,
            AgentBudgetReservation reservation,
            int replanCount,
            Instant startedAt,
            CancellationTokenSource.CancellationToken cancellationToken,
            long readyRunVersion) {

        public PreparedGroundedAnswer {
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (usage == null) usage = AgentUsage.zero();
            if (reservation == null) reservation = AgentBudgetReservation.zero();
        }
    }
}
