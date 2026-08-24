package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.SettlementResult;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource.CancellationToken;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.CompletedStepSummary;
import com.xxx.ragdoc.application.chat.tool.EvidenceListOutput;
import com.xxx.ragdoc.application.chat.tool.ToolExecutor;
import com.xxx.ragdoc.application.chat.tool.ToolOutput;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.application.chat.tool.ToolStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7c / EMS-PR7 §2 + §3: 单 Phase 执行器 — 不写终态的 KEEP_EXECUTING 模式。
 *
 * <p>用于 {@code PlannedAgentPipeline} 把 Planner 生成的 Phase 内 Step 跑完, 累积 Evidence + 步进
 * Usage/Reservation, 把历史 Tool Signature 写进 usedToolSignatures — <b>不</b>写 READY_TO_ANSWER /
 * REFUSED_NO_EVIDENCE。Pipeline 在 Sufficiency 判断后通过 {@code ComparisonRunFinalizer} 或等价 Run-level CAS
 * 决定终态。
 *
 * <p><b>状态机不变量 (Revision §2.1)</b>: Phase 在 {@link AgentRunStatus#EXECUTING} 时执行; 若中途 cancel /
 * deadline / 预算超限 / required Tool 失败 → 立即返回 {@link PhaseExecutionResult#prematureTerminal()} 给的非
 * null 值, Pipeline 不再 Sufficiency。Phase 本身<b>不</b>把 Run 转终态 CAS — Pipeline 决策。
 *
 * <p><b>钱包字段不重置</b>: usage/reservation 由 PhaseExecutionContext.prior* 续作; Usage + Reservation +=
 * Phase 内增量, 累积后通过 Result 返回。
 *
 * <p><b>工具在事务外</b>: ToolExecutor 调用在普通上下文 (跟随 PR-6b.3 同样的契约); Coordinator 方法 已 REQUIRES_NEW 隔离事务边界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunPhaseExecutor {

    private static final int MAX_CAS_RETRIES = 3;

    private final AgentPersistenceCoordinator coordinator;
    private final AgentBudgetManager budgetManager;
    private final ToolExecutor toolExecutor;
    private final EvidenceAccumulatorFactory evidenceFactory;
    private final Clock clock = Clock.systemUTC();

    /**
     * 执行 Phase 内一组 Step。
     *
     * @param handle {@link AgentRunHandle} (要求 Run 处于 EXECUTING)
     * @param phasePlanSteps 本 Phase 要执行的所有 Step (sorted, required info 已带)
     * @param priorCompletedIds 截至本 Phase 已 SUCCEEDED 的 required Step IDs (依赖检查)
     * @param reqIdToStepId 每个 Requirement 关联的当前 Phase Step ID (Evidence provenance)
     * @param context Prior Phase state (优先 Phase 0 用 {@link PhaseExecutionContext#initial})
     * @param cancellation 取消信号
     * @return PhaseExecutionResult (KEEP_EXECUTING semantics, 不写 Run 终态)
     */
    public PhaseExecutionResult executePhase(
            AgentRunHandle handle,
            List<com.xxx.ragdoc.application.chat.agent.AgentToolStep> phasePlanSteps,
            Set<String> priorCompletedIds,
            Map<String, String>
                    reqIdToStepId /* allows evidence metadata.requirementIds 注入; PR-7c Pipeline 复用 */,
            PhaseExecutionContext context,
            CancellationToken cancellation) {
        if (handle == null) throw new IllegalArgumentException("handle");
        if (phasePlanSteps == null) phasePlanSteps = List.of();
        if (context == null) {
            context = PhaseExecutionContext.initial(handle.run().version(), Instant.now(clock));
        }
        if (cancellation == null) cancellation = CancellationToken.never();

        EvidenceAccumulator accumulator =
                evidenceFactory.create(
                        handle.tenantId(),
                        handle.policy().maxEvidence(),
                        handle.policy().maxEvidenceTokens());
        // restore prior evidence
        for (Evidence e : context.priorEvidence()) {
            accumulator.accept(context.completedSteps().size(), 0, e);
        }

        Set<String> succeededSteps = new HashSet<>(priorCompletedIds);
        AgentUsage runtimeUsage = context.priorUsage();
        AgentBudgetReservation runtimeReservation = context.priorReservation();
        Set<String> usedSignatures = new LinkedHashSet<>(context.usedToolSignatures());
        List<CompletedStepSummary> completedSummaries = new ArrayList<>(context.completedSteps());
        List<String> executedStepIds = new ArrayList<>();
        int sequenceCursor = context.nextStepSequence();
        long currentRunVersion = context.currentRunVersion();
        boolean requiredStepFailed = false;
        String failureReasonCode = null;
        AgentRunStatus prematureTerminal = null;

        java.util.Set<String> discoveredEntities = new LinkedHashSet<>();

        for (com.xxx.ragdoc.application.chat.agent.AgentToolStep planStep : phasePlanSteps) {
            if (cancellation.isCancelled()) {
                log.info("phase.cancelled step={} run={}", planStep.stepId(), handle.run().runId());
                prematureTerminal = AgentRunStatus.CANCELLED;
                failureReasonCode = "CANCELLED";
                break;
            }
            if (isExpired(clock, handle.policy().deadline())) {
                log.info(
                        "phase.deadline_exceeded step={} run={}",
                        planStep.stepId(),
                        handle.run().runId());
                prematureTerminal = AgentRunStatus.TIMED_OUT;
                failureReasonCode = "DEADLINE_EXCEEDED";
                break;
            }
            // 依赖检查 — dependsOn 必须 SUCCEEDED (initial Phase 内无依赖, Replan Phase 跨 Phase 依赖已含在
            // priorCompletedIds)
            boolean depOk = true;
            String missingDep = null;
            for (String dep : planStep.dependsOn()) {
                if (!succeededSteps.contains(dep)) {
                    depOk = false;
                    missingDep = dep;
                    break;
                }
            }
            if (!depOk) {
                log.info(
                        "phase.dependency_not_satisfied step={} missing={} run={}",
                        planStep.stepId(),
                        missingDep,
                        handle.run().runId());
                requiredStepFailed = planStep.required();
                failureReasonCode = "DEPENDENCY_NOT_SATISFIED:" + missingDep;
                prematureTerminal = planStep.required() ? AgentRunStatus.TOOL_FAILED : null;
                if (prematureTerminal != null) break;
                continue;
            }

            // budget
            BudgetDecision decision =
                    budgetManager.evaluate(
                            handle.policy().budget(),
                            runtimeUsage,
                            runtimeReservation,
                            ReservationRequest.forRealToolCall());
            if (decision instanceof BudgetDecision.Denied d) {
                log.info(
                        "phase.budget_denied step={} dim={} run={}",
                        planStep.stepId(),
                        d.dimension(),
                        handle.run().runId());
                failureReasonCode = "BUDGET_EXCEEDED_" + d.dimension();
                prematureTerminal = AgentRunStatus.BUDGET_EXCEEDED;
                // 当前 Step 标 SKIPPED_BUDGET (调用 Coordinator.transitionStep 不让 Run 终止)
                AgentStepRecord refreshed =
                        coordinator.reloadStep(handle.run().runId(), planStep.stepId());
                coordinator.transitionStep(
                        handle.run().runId(),
                        planStep.stepId(),
                        refreshed.version(),
                        Set.of(AgentStepStatus.PENDING),
                        AgentStepStatus.SKIPPED_BUDGET,
                        AgentStepUpdate.empty());
                break;
            }
            BudgetDecision.Allowed allowed = (BudgetDecision.Allowed) decision;

            // reserve + markRunning (CAS 重试)
            AgentStepRecord stepRec =
                    coordinator.reloadStep(handle.run().runId(), planStep.stepId());
            if (stepRec.status() != AgentStepStatus.PENDING) {
                log.info(
                        "phase.step_not_pending skip step={} status={} run={}",
                        planStep.stepId(),
                        stepRec.status(),
                        handle.run().runId());
                if (stepRec.status().isTerminal()) {
                    succeededSteps.add(planStep.stepId());
                    continue;
                }
                // RESERVED/RUNNING 状态 — PR-7c 不恢复; 视为 fail
                failureReasonCode = "STEP_NOT_PENDING:" + stepRec.status();
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                break;
            }

            AgentPersistenceCoordinator.ReservationResult reservation;
            try {
                final AgentUsage usageSnapshot = runtimeUsage;
                final long runVersionSnapshot = currentRunVersion;
                reservation =
                        retryCasReservation(
                                () ->
                                        coordinator.reserveStep(
                                                handle.run().runId(),
                                                runVersionSnapshot,
                                                Set.of(AgentRunStatus.EXECUTING),
                                                usageSnapshot,
                                                allowed,
                                                planStep.stepId(),
                                                stepRec.version()));
            } catch (AgentCasConflictException ex) {
                failureReasonCode = "CAS_RESERVE_EXHAUSTED";
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                break;
            }
            runtimeReservation = reservation.newReservation();
            currentRunVersion = reservation.newRunVersion();
            long stepVersionReserved = reservation.newStepVersion();

            long stepVersionRunning;
            try {
                stepVersionRunning =
                        coordinator.markStepRunning(
                                handle.run().runId(),
                                planStep.stepId(),
                                stepVersionReserved,
                                AgentStepUpdate.empty());
            } catch (AgentCasConflictException ex) {
                failureReasonCode = "CAS_MARK_RUNNING_FAILED";
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                break;
            }

            // 事务外 Tool 调用
            ToolResult<? extends ToolOutput> toolResult =
                    toolExecutor.execute(
                            planStep.toolName(),
                            planStep.toolVersion(),
                            planStep.input(),
                            new ToolExecutor.ToolCallRequest(
                                    handle.run().requestId(),
                                    handle.run().runId(),
                                    handle.policy().deadline(),
                                    handle.run().indexVersion()));

            // accumulate evidence + provenance
            List<Evidence> stepEvidence = extractEvidence(toolResult);
            // 注入 metadata.requirementIds 让 EvidenceSufficiencyJudge 可索引
            String reqId =
                    reqIdToStepId == null
                            ? null
                            : reqIdToStepId.entrySet().stream()
                                    .filter(e -> e.getValue().equals(planStep.stepId()))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElse(null);
            int resultIdx = 0;
            for (Evidence e : stepEvidence) {
                Map<String, Object> augmented =
                        augmentMetadata(e.metadata(), planStep.stepId(), reqId);
                Evidence reattrib =
                        Evidence.of(
                                handle.tenantId(),
                                e.documentId(),
                                e.chunkId(),
                                e.documentVersion(),
                                e.content(),
                                e.retrievalScore(),
                                e.rerankScore(),
                                e.sourceTool(),
                                augmented);
                accumulator.accept(sequenceCursor, resultIdx++, reattrib);
            }
            executedStepIds.add(planStep.stepId());

            // status mapping
            boolean hasEvidence = !stepEvidence.isEmpty();
            AgentStepStatus terminal =
                    ToolStatusMapper.toStepStatus(toolResult.status(), hasEvidence);
            String errorCode =
                    toolResult.error() != null
                            ? toolResult.error().errorCode()
                            : (toolResult.status() == ToolStatus.SUCCESS
                                    ? ""
                                    : toolResult.status().name());
            boolean replayed = isReplayedMetadata(toolResult);
            boolean deduped = isDedupMetadata(toolResult);
            StepSettlement settlement =
                    replayed
                            ? StepSettlement.replay(terminal, errorCode)
                            : (deduped
                                    ? StepSettlement.dedup(terminal, errorCode)
                                    : StepSettlement.realTool(
                                            terminal,
                                            errorCode,
                                            // P2 接入(原恒 0): 工具输入按输入 token 记账,
                                            // 证据内容按输出 token 记账(TokenEstimator 保守估算)
                                            estimateTokens(planStep),
                                            estimateTokens(stepEvidence),
                                            java.math.BigDecimal.ZERO));
            AgentBudgetManager.SettleResult settled =
                    budgetManager.settle(runtimeUsage, runtimeReservation, settlement);
            runtimeUsage = settled.newUsage();
            runtimeReservation = settled.newReservation();

            // tool signature for loop detect
            String sig = signatureOf(planStep);
            usedSignatures.add(sig);

            // build CompletedStepSummary (requirementIds derived from reqIdToStepId)
            List<String> targetReqIds =
                    reqIdToStepId == null
                            ? List.of()
                            : reqIdToStepId.entrySet().stream()
                                    .filter(e -> e.getValue().equals(planStep.stepId()))
                                    .map(Map.Entry::getKey)
                                    .toList();
            completedSummaries.add(
                    new CompletedStepSummary(
                            planStep.stepId(),
                            planStep.toolName(),
                            planStep.toolVersion(),
                            sig,
                            stepEvidence.size(),
                            targetReqIds,
                            terminal.name(),
                            Map.of()));

            AgentStepUpdate stepUpdate =
                    new AgentStepUpdate(
                            toolResult.callId(),
                            stepEvidence.size(),
                            accumulator.toIdsWithCount(),
                            toolResult.latencyMs(),
                            errorCode,
                            toolResult.retryable(),
                            replayed,
                            deduped,
                            Instant.now(clock),
                            Instant.now(clock));

            try {
                final long runVBeforeSettle = currentRunVersion;
                SettlementResult sr =
                        retryCasSettle(
                                () ->
                                        coordinator.settleStep(
                                                handle.run().runId(),
                                                runVBeforeSettle,
                                                Set.of(AgentRunStatus.EXECUTING),
                                                settled,
                                                accumulator.toIdsWithCount(),
                                                accumulator.toIdsWithCount().size(),
                                                planStep.stepId(),
                                                stepVersionRunning,
                                                terminal,
                                                stepUpdate));
                currentRunVersion = sr.newRunVersion();
            } catch (AgentCasConflictException ex) {
                failureReasonCode = "CAS_SETTLE_EXHAUSTED";
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                break;
            }

            // required failure
            if (planStep.required()) {
                if (terminal == AgentStepStatus.FAILED_TERMINAL) {
                    requiredStepFailed = true;
                    failureReasonCode = "REQUIRED_TOOL_FAILED:" + errorCode;
                    prematureTerminal = AgentRunStatus.TOOL_FAILED;
                    break;
                }
                if (terminal == AgentStepStatus.PERMISSION_DENIED) {
                    requiredStepFailed = true;
                    failureReasonCode = "PERMISSION_DENIED";
                    prematureTerminal = AgentRunStatus.REFUSED_PERMISSION;
                    break;
                }
                if (terminal == AgentStepStatus.TIMED_OUT) {
                    requiredStepFailed = true;
                    failureReasonCode = "TOOL_TIMEOUT";
                    prematureTerminal = AgentRunStatus.TIMED_OUT;
                    break;
                }
            }
            if (terminal == AgentStepStatus.SUCCEEDED || terminal == AgentStepStatus.EMPTY) {
                succeededSteps.add(planStep.stepId());
                // 简化 discoveredEntities: 从 step input 的 query + entities 提取 (PR-7c.2
                // ProgressDetector 也用)
                // 这里只把 Tool input 参数放进 discoveredEntities (semantic_search query 内 token 太粗, 留空)
            }
            sequenceCursor++;
        }

        List<Evidence> latestAccumulated = accumulator.snapshot();
        // newEvidence = latest accumulated - prior (by evidenceId)
        java.util.Set<String> priorIds = new HashSet<>();
        for (Evidence e : context.priorEvidence()) priorIds.add(e.evidenceId());
        List<Evidence> newEvidence = new ArrayList<>();
        for (Evidence e : latestAccumulated) {
            if (priorIds.add(e.evidenceId())) newEvidence.add(e);
        }

        return new PhaseExecutionResult(
                handle.run().runId(),
                context.phaseIndex(),
                currentRunVersion,
                executedStepIds,
                newEvidence,
                latestAccumulated,
                runtimeUsage,
                runtimeReservation,
                List.copyOf(completedSummaries),
                Set.copyOf(usedSignatures),
                Set.copyOf(discoveredEntities),
                requiredStepFailed,
                failureReasonCode,
                prematureTerminal);
    }

    // ─── helpers ────────────────────────────────────────

    private static boolean isExpired(Clock c, Instant deadline) {
        return deadline != null && Instant.now(c).isAfter(deadline);
    }

    private List<Evidence> extractEvidence(ToolResult<? extends ToolOutput> r) {
        if (r != null && r.output() instanceof EvidenceListOutput elo) return elo.evidences();
        return List.of();
    }

    private static boolean isReplayedMetadata(ToolResult<?> r) {
        return r != null && Boolean.TRUE.equals(r.metadata().get("harness_replayed"));
    }

    private static boolean isDedupMetadata(ToolResult<?> r) {
        return r != null && Boolean.TRUE.equals(r.metadata().get("deduplicated"));
    }

    /** P2: 步骤 token 估算 — 输入=工具步骤序列化, 输出=证据内容列表。 */
    private static long estimateTokens(Object o) {
        if (o == null) return 0L;
        if (o instanceof java.util.List<?> list) {
            long sum = 0;
            for (var x : list) sum += estimateTokens(x);
            return sum;
        }
        return TokenEstimator.estimate(String.valueOf(o));
    }

    private static String signatureOf(com.xxx.ragdoc.application.chat.agent.AgentToolStep s) {
        return s.toolName()
                + "|"
                + s.toolVersion()
                + "|"
                + (s.input() == null ? "" : s.input().normalizedForDedup());
    }

    /** 把 sourceStepId + requirementIds 注入 metadata, 让 SufficiencyJudge 可索引。 */
    private static Map<String, Object> augmentMetadata(
            Map<String, Object> base, String stepId, String reqId) {
        Map<String, Object> augmented = base == null ? new HashMap<>() : new HashMap<>(base);
        augmented.put("sourceStepId", stepId);
        if (reqId != null) {
            Object existing = augmented.get("requirementIds");
            List<String> reqList = new ArrayList<>();
            if (existing instanceof List<?> list) {
                for (Object o : list) if (o instanceof String x) reqList.add(x);
            }
            if (!reqList.contains(reqId)) reqList.add(reqId);
            augmented.put("requirementIds", List.copyOf(reqList));
        }
        return Map.copyOf(augmented);
    }

    private AgentPersistenceCoordinator.ReservationResult retryCasReservation(
            java.util.function.Supplier<AgentPersistenceCoordinator.ReservationResult> sup) {
        AgentCasConflictException last = null;
        for (int i = 0; i < MAX_CAS_RETRIES; i++) {
            try {
                return sup.get();
            } catch (AgentCasConflictException e) {
                last = e;
            }
        }
        throw last;
    }

    private SettlementResult retryCasSettle(java.util.function.Supplier<SettlementResult> sup) {
        AgentCasConflictException last = null;
        for (int i = 0; i < MAX_CAS_RETRIES; i++) {
            try {
                return sup.get();
            } catch (AgentCasConflictException e) {
                last = e;
            }
        }
        throw last;
    }
}
