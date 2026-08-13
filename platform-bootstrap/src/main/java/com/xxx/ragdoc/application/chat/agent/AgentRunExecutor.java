package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.tool.EvidenceListOutput;
import com.xxx.ragdoc.application.chat.tool.ToolExecutor;
import com.xxx.ragdoc.application.chat.tool.ToolOutput;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.application.chat.tool.ToolStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6b.3 / EMS-PR6 §7: 确定性 Agent Run 执行器。
 *
 * <p>按 PlanValidator 提供的稳定拓扑顺序<b>串行</b>执行 Step (Revision §3 — 不并行 DAG)。
 *
 * <p>每 Step 流水 (Revision §10 §11):
 *
 * <ol>
 *   <li>检查 cancellation / deadline
 *   <li>检查依赖 SUCCEEDED
 *   <li>BudgetManager.evaluate 联合预算 — Denied (hard budget) → 当前 step SKIPPED_BUDGET + Run
 *       BUDGET_EXCEEDED + 后续 step CANCELLED (Revision §7)
 *   <li>Coordinator.reserveStep (run reservation CAS + step PENDING→RESERVED 单事务)
 *   <li>Coordinator.markStepRunning (step RESERVED→RUNNING 单事务, 独立)
 *   <li><b>事务外</b>调 ToolExecutor.execute (Milvus/Sparse/Harness 不进 DB 事务; Revision §11.4 — Spring
 *       IT 在 PR-6b.3 AgentRunExecutorToolTxIT 实测)
 *   <li>ToolStatusMapper → AgentStepStatus
 *   <li>EvidenceAccumulator.accept (per-Run instance)
 *   <li>Coordinator.settleStep (一次 run CAS: usage+reservation+evidenceIds + step terminal CAS 单事务)
 *   <li>optional Step 失败 → continue; required Step 失败 → break + cleanup
 * </ol>
 *
 * <p>Run 终态规则:
 *
 * <ul>
 *   <li>所有 Step 终态 + Accumulator 非空 + 没有 required Step EMPTY/FAILED/PERMISSION/TIMEOUT →
 *       READY_TO_ANSWER
 *   <li>所有 Step 终态 + 0 Evidence <b>或</b> 任何 required Step EMPTY/FAILED_TERMINAL/
 *       PERMISSION_DENIED/TIMED_OUT → READY_TO_ANSWER → REFUSED_NO_EVIDENCE
 *       reasonCode=REQUIRED_EVIDENCE_MISSING (Revision §6)
 *   <li>Tool FAILED_TERMINAL (required) → Run TOOL_FAILED
 *   <li>PERMISSION_DENIED (required + failOn=true) → REFUSED_PERMISSION
 *   <li>TIMED_OUT (deadline) → TIMED_OUT
 *   <li>CANCELLED → CANCELLED
 *   <li>BUDGET_EXCEEDED → BUDGET_EXCEEDED
 * </ul>
 *
 * <p>正常结束前 Cleanup Pass: 所有 PENDING/RESERVED/RUNNING/FAILED_RETRYABLE → CANCELLED/FAILED_TERMINAL
 * (Revision §1.9 — 禁止遗留非终态 Step)。
 *
 * <p>本执行器不启用 AGENTIC; {@link com.xxx.ragdoc.application.chat.pipeline.ChatOrchestrator} 仍 422。
 */
@Slf4j
@Component
public class AgentRunExecutor {

    private final AgentPersistenceCoordinator coordinator;
    private final AgentBudgetManager budgetManager;
    private final ToolExecutor toolExecutor;
    private final EvidenceAccumulatorFactory evidenceFactory;
    private final AgentExecutionLeaseService leaseService;
    private final Clock clock = Clock.systemUTC();

    @org.springframework.beans.factory.annotation.Value("${rag.agent.lease.ttl-seconds:30}")
    private long leaseTtlSeconds = 30;

    @org.springframework.beans.factory.annotation.Value("${rag.agent.lease.heartbeat-seconds:10}")
    private long heartbeatSeconds = 10;

    public AgentRunExecutor(
            AgentPersistenceCoordinator coordinator,
            AgentBudgetManager budgetManager,
            ToolExecutor toolExecutor,
            EvidenceAccumulatorFactory evidenceFactory,
            AgentExecutionLeaseService leaseService) {
        this.coordinator = coordinator;
        this.budgetManager = budgetManager;
        this.toolExecutor = toolExecutor;
        this.evidenceFactory = evidenceFactory;
        this.leaseService = leaseService;
    }

    /** 默认 CAS 重试上限 (Revision §4.6); 后续抽到 @ConfigurationProperties。 */
    private static final int MAX_CAS_RETRIES = 3;

    public AgentRunResult execute(
            DeterministicExecutionPlan plan,
            AgentExecutionPolicy policy,
            InitializedRun init,
            String tenantId,
            String requestId,
            CancellationTokenSource.CancellationToken cancellation) {
        String runId = init.run().runId();
        String ownerId = executionOwnerId();
        Duration ttl = Duration.ofSeconds(Math.max(10, leaseTtlSeconds));
        if (!leaseService.claim(runId, ownerId, ttl)) {
            throw new AgentLeaseUnavailableException("Agent Run 已由其他实例执行: " + runId);
        }
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledExecutorService heartbeat = startHeartbeat(runId, ownerId, ttl, leaseLost);
        try {
            return executeWithLease(
                    plan, policy, init, tenantId, requestId, cancellation, leaseLost, ownerId, ttl);
        } finally {
            heartbeat.shutdownNow();
            try {
                leaseService.release(runId, ownerId);
            } catch (Exception e) {
                // 释放失败只会让租约自然过期，不能覆盖已经完成的业务结果。
                log.warn(
                        "agent.lease_release_failed run_id={}, owner_id={}, error={}",
                        runId,
                        ownerId,
                        e.getMessage());
            }
        }
    }

    private AgentRunResult executeWithLease(
            DeterministicExecutionPlan plan,
            AgentExecutionPolicy policy,
            InitializedRun init,
            String tenantId,
            String requestId,
            CancellationTokenSource.CancellationToken cancellation,
            AtomicBoolean leaseLost,
            String ownerId,
            Duration ttl) {
        AgentRunHandle handle =
                AgentRunHandle.from(init, plan, policy, cancellation, tenantId, clock);
        EvidenceAccumulator accumulator =
                evidenceFactory.create(tenantId, policy.maxEvidence(), policy.maxEvidenceTokens());
        AgentUsage runtimeUsage = init.run().usage();
        AgentBudgetReservation runtimeReservation = init.run().reservation();
        List<String> runtimeEvidenceIds = new ArrayList<>();
        int completedSteps = 0;
        int realToolCalls = 0;
        int replayedCalls = 0;
        int dedupHits = 0;
        boolean requiredEvidenceMissing = false;
        AgentRunStatus prematureTerminal = null;
        String prematureReasonCode = null;
        Instant startedAt = Instant.now(clock);

        Map<String, AgentStepRecord> stepById = new HashMap<>();
        for (AgentStepRecord s : init.steps()) {
            stepById.put(s.stepId(), s);
        }
        Set<String> succeededSteps = new HashSet<>();
        int seq = 0;
        for (AgentToolStep planStep : plan.steps()) {
            AgentStepRecord persistedStep = stepById.get(planStep.stepId());

            ensureLeaseOwned(leaseLost, handle.run().runId(), ownerId, ttl);

            // 1. cancellation
            if (cancellation.isCancelled()) {
                log.info(
                        "agent.run cancelled at step {} run={}",
                        planStep.stepId(),
                        handle.run().runId());
                cancelStepAndMarkPremature(persistedStep, handle);
                prematureTerminal = AgentRunStatus.CANCELLED;
                prematureReasonCode = "CANCELLED";
                break;
            }
            // 2. Run deadline
            if (isExpired(clock, policy.deadline())) {
                log.info(
                        "agent.run deadline exceeded before step {} run={}",
                        planStep.stepId(),
                        handle.run().runId());
                transitionStepTerminal(
                        persistedStep, handle, AgentStepStatus.TIMED_OUT, "DEADLINE_EXCEEDED");
                prematureTerminal = AgentRunStatus.TIMED_OUT;
                prematureReasonCode = "DEADLINE_EXCEEDED";
                break;
            }
            // 3. 依赖检查 — 所有 dependsOn 必须 SUCCEEDED (否则 SKIPPED_DEPENDENCY → FAILED_TERMINAL)
            AgentStepRecord refreshed =
                    coordinator.reloadStep(handle.run().runId(), planStep.stepId());
            DependencyCheckResult dep = checkDependencies(planStep, succeededSteps);
            if (dep.failed) {
                log.info(
                        "agent.step dependency not satisfied step={} missing={} run={}",
                        planStep.stepId(),
                        dep.missing,
                        handle.run().runId());
                if (planStep.required()) {
                    stepById.put(
                            planStep.stepId(),
                            transitionStepTerminal(
                                    refreshed,
                                    handle,
                                    AgentStepStatus.FAILED_TERMINAL,
                                    "DEPENDENCY_NOT_SATISFIED"));
                    if (planStep.required()) {
                        requiredEvidenceMissing = true;
                    }
                    if (policy.continueOnEmptyResult()) {
                        seq++;
                        continue;
                    }
                    prematureTerminal = AgentRunStatus.TOOL_FAILED;
                    prematureReasonCode = "DEPENDENCY_NOT_SATISFIED";
                    break;
                } else {
                    transitionStepTerminal(
                            refreshed,
                            handle,
                            AgentStepStatus.FAILED_TERMINAL,
                            "DEPENDENCY_NOT_SATISFIED");
                    seq++;
                    continue;
                }
            }

            // 4. budget 预留
            ReservationRequest req = ReservationRequest.forRealToolCall();
            BudgetDecision decision =
                    budgetManager.evaluate(policy.budget(), runtimeUsage, runtimeReservation, req);
            if (decision instanceof BudgetDecision.Denied d) {
                log.info(
                        "agent.budget denied step={} dimension={} run={}",
                        planStep.stepId(),
                        d.dimension(),
                        handle.run().runId());
                AgentStepRecord afterSkipped =
                        transitionStepTerminal(
                                refreshed,
                                handle,
                                AgentStepStatus.SKIPPED_BUDGET,
                                "BUDGET_EXCEEDED");
                stepById.put(planStep.stepId(), afterSkipped);
                // Revision §7 — hard budget 任何维度拒绝都终止整个 Run, 后续 Step CANCELLED
                prematureTerminal = AgentRunStatus.BUDGET_EXCEEDED;
                prematureReasonCode = "BUDGET_EXCEEDED_" + d.dimension();
                break;
            }
            BudgetDecision.Allowed allowed = (BudgetDecision.Allowed) decision;

            // 5+6. reserveStep + markStepRunning (CAS 重试)
            AgentRunRecord runBefore = handle.run();
            final AgentUsage usageAtReserve = runtimeUsage;
            AgentPersistenceCoordinator.ReservationResult reserveResult;
            try {
                reserveResult =
                        retryCas(
                                () ->
                                        coordinator.reserveStep(
                                                runBefore.runId(),
                                                runBefore.version(),
                                                Set.of(AgentRunStatus.EXECUTING),
                                                usageAtReserve,
                                                allowed,
                                                planStep.stepId(),
                                                refreshed.version()));
            } catch (AgentCasConflictException e) {
                log.warn(
                        "agent.reserveStep exhausted retries run={} step={} err={}",
                        runBefore.runId(),
                        planStep.stepId(),
                        e.getMessage());
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                prematureReasonCode = "CAS_RESERVE_EXHAUSTED";
                break;
            }
            runtimeReservation = reserveResult.newReservation();
            long stepVersionAfterReserve = reserveResult.newStepVersion();
            long newRunVersionAfterReserve = reserveResult.newRunVersion();
            long stepVersionRunning;
            try {
                stepVersionRunning =
                        retryCasLong(
                                () ->
                                        coordinator.markStepRunning(
                                                runBefore.runId(),
                                                planStep.stepId(),
                                                stepVersionAfterReserve,
                                                AgentStepUpdate.empty()));
            } catch (AgentCasConflictException e) {
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                prematureReasonCode = "CAS_MARK_RUNNING_EXHAUSTED";
                break;
            }

            // 7. 事务外调 ToolExecutor (Milvus / Sparse / Harness 不进 DB 事务)
            ToolResult<? extends ToolOutput> toolResult =
                    invokeToolOutsideTransaction(planStep, handle, policy, requestId);

            // 工具调用期间一旦丢租约，旧实例不得结算结果覆盖新 owner。
            ensureLeaseOwned(leaseLost, handle.run().runId(), ownerId, ttl);

            // 8. EvidenceAccumulator (per-Run)
            boolean replayed = isReplayed(toolResult);
            boolean deduplicated = isDeduplicated(toolResult);
            List<Evidence> stepEvidence = extractEvidence(toolResult);
            int evidenceResultIndex = 0;
            for (Evidence e : stepEvidence) {
                accumulator.accept(seq, evidenceResultIndex++, e);
            }
            // 更新 runtime evidenceIds (累积快照)
            runtimeEvidenceIds.clear();
            runtimeEvidenceIds.addAll(accumulator.toIdsWithCount());

            // 9. 计算 settlement + step 终态 + RunUsage/Reservation settle
            AgentStepStatus terminal =
                    ToolStatusMapper.toStepStatus(toolResult.status(), !stepEvidence.isEmpty());
            String errorCode =
                    toolResult.error() != null
                            ? toolResult.error().errorCode()
                            : (toolResult.status() == ToolStatus.SUCCESS
                                    ? ""
                                    : toolResult.status().name());
            StepSettlement settlement =
                    buildSettlement(toolResult, terminal, errorCode, replayed, deduplicated);
            AgentBudgetManager.SettleResult settled =
                    budgetManager.settle(runtimeUsage, runtimeReservation, settlement);
            runtimeUsage = settled.newUsage();
            runtimeReservation = settled.newReservation();
            if (settlement.outcome() == StepSettlement.Outcome.REAL_TOOL) {
                realToolCalls++;
            } else if (settlement.outcome() == StepSettlement.Outcome.LOGICAL_STEP_REPLAY) {
                replayedCalls++;
            } else if (settlement.outcome() == StepSettlement.Outcome.LOGICAL_STEP_DEDUP) {
                dedupHits++;
            }

            AgentStepUpdate stepUpdate =
                    new AgentStepUpdate(
                            toolResult.callId(),
                            stepEvidence.size(),
                            accumulator.toIdsWithCount(),
                            toolResult.latencyMs(),
                            errorCode,
                            toolResult.retryable(),
                            replayed,
                            deduplicated,
                            Instant.now(clock) /* startedAt approx */,
                            Instant.now(clock) /* completedAt */);

            long stepFinalVersion;
            long runFinalVersion;
            final String runIdFinal = handle.run().runId();
            try {
                AgentPersistenceCoordinator.SettlementResult sr =
                        retryCasSettle(
                                () ->
                                        coordinator.settleStep(
                                                runIdFinal,
                                                newRunVersionAfterReserve,
                                                Set.of(AgentRunStatus.EXECUTING),
                                                settled,
                                                runtimeEvidenceIds,
                                                runtimeEvidenceIds.size(),
                                                planStep.stepId(),
                                                stepVersionRunning,
                                                terminal,
                                                stepUpdate));
                stepFinalVersion = sr.newStepVersion();
                runFinalVersion = sr.newRunVersion();
            } catch (AgentCasConflictException e) {
                log.warn(
                        "agent.settleStep CAS exhausted run={} step={} err={}",
                        handle.run().runId(),
                        planStep.stepId(),
                        e.getMessage());
                prematureTerminal = AgentRunStatus.SYSTEM_FAILED;
                prematureReasonCode = "CAS_SETTLE_EXHAUSTED";
                break;
            }
            completedSteps++;

            // 更新内存 handle.run.version
            handle = handle.withRunVersion(runFinalVersion);
            // 10. required Step 失败处理
            if (terminal == AgentStepStatus.EMPTY && planStep.required()) {
                requiredEvidenceMissing = true;
                if (!policy.continueOnEmptyResult()) {
                    prematureTerminal = AgentRunStatus.REFUSED_NO_EVIDENCE;
                    prematureReasonCode = "REQUIRED_EVIDENCE_MISSING";
                    succeededSteps.add(planStep.stepId());
                    break;
                }
                // continueOnEmptyResult=true 时继续收集其它 step evidence
            }
            if (planStep.required()
                    && (terminal == AgentStepStatus.FAILED_TERMINAL
                            || terminal == AgentStepStatus.PERMISSION_DENIED
                            || terminal == AgentStepStatus.TIMED_OUT)) {
                if (terminal == AgentStepStatus.FAILED_TERMINAL) {
                    prematureTerminal = AgentRunStatus.TOOL_FAILED;
                    prematureReasonCode = errorCode.isEmpty() ? "TOOL_FAILED" : errorCode;
                } else if (terminal == AgentStepStatus.PERMISSION_DENIED) {
                    if (policy.failOnPermissionDenied()) {
                        prematureTerminal = AgentRunStatus.REFUSED_PERMISSION;
                        prematureReasonCode = "PERMISSION_DENIED";
                    } else {
                        requiredEvidenceMissing = true;
                    }
                } else if (terminal == AgentStepStatus.TIMED_OUT) {
                    prematureTerminal = AgentRunStatus.TIMED_OUT;
                    prematureReasonCode = "TOOL_TIMEOUT";
                }
                if (prematureTerminal != null) {
                    break;
                }
            } else {
                if (terminal == AgentStepStatus.SUCCEEDED) {
                    succeededSteps.add(planStep.stepId());
                } else if (terminal == AgentStepStatus.EMPTY) {
                    succeededSteps.add(planStep.stepId());
                }
            }
            seq++;
        }

        // Cleanup 和 Run 终态都会写 DB，必须在当前 owner 仍持有租约时执行。
        ensureLeaseOwned(leaseLost, handle.run().runId(), ownerId, ttl);

        // Cleanup pass — 把所有仍在 PENDING/RESERVED/RUNNING/FAILED_RETRYABLE 的 step 终结
        cleanupNonTerminalSteps(handle, prematureTerminal != null);

        // Run 终态写
        AgentRunStatus finalStatus;
        String finalReasonCode;
        if (prematureTerminal != null) {
            finalStatus = prematureTerminal;
            finalReasonCode = prematureReasonCode;
        } else {
            // 全部 Step 完成, 判 READY_TO_ANSWER 或 REFUSED_NO_EVIDENCE
            if (accumulator.size() > 0 && !requiredEvidenceMissing) {
                // EXECUTING → READY_TO_ANSWER
                boolean ok =
                        coordinator.transitionRun(
                                handle.run().runId(),
                                handle.run().version(),
                                Set.of(AgentRunStatus.EXECUTING),
                                AgentRunStatus.READY_TO_ANSWER,
                                "EVIDENCE_READY",
                                runtimeUsage,
                                runtimeReservation);
                if (!ok) {
                    log.warn(
                            "agent.run EXECUTING→READY_TO_ANSWER CAS 失败 run={}",
                            handle.run().runId());
                }
                finalStatus = AgentRunStatus.READY_TO_ANSWER;
                finalReasonCode = "EVIDENCE_READY";
            } else {
                // 先 EXECUTING → READY_TO_ANSWER 再 → REFUSED_NO_EVIDENCE (状态机不变量)
                long v0 = handle.run().version();
                boolean toReady =
                        coordinator.transitionRun(
                                handle.run().runId(),
                                v0,
                                Set.of(AgentRunStatus.EXECUTING),
                                AgentRunStatus.READY_TO_ANSWER,
                                "no_evidence",
                                runtimeUsage,
                                runtimeReservation);
                // reload 拿新 version (conservative — 没法 reload 时退回 v0+1)
                boolean toRefused =
                        coordinator.transitionRun(
                                handle.run().runId(),
                                toReady ? v0 + 1 : v0,
                                Set.of(AgentRunStatus.READY_TO_ANSWER),
                                AgentRunStatus.REFUSED_NO_EVIDENCE,
                                requiredEvidenceMissing
                                        ? "REQUIRED_EVIDENCE_MISSING"
                                        : "NO_EVIDENCE",
                                runtimeUsage,
                                runtimeReservation);
                if (!toReady || !toRefused) {
                    log.warn(
                            "agent.run EXECUTING→READY_TO_ANSWER→REFUSED_NO_EVIDENCE CAS 失败 run={}",
                            handle.run().runId());
                }
                finalStatus = AgentRunStatus.REFUSED_NO_EVIDENCE;
                finalReasonCode =
                        requiredEvidenceMissing ? "REQUIRED_EVIDENCE_MISSING" : "NO_EVIDENCE";
            }
        }
        if (prematureTerminal != null) {
            boolean ok =
                    coordinator.transitionRun(
                            handle.run().runId(),
                            handle.run().version(),
                            Set.of(AgentRunStatus.EXECUTING, AgentRunStatus.READY_TO_ANSWER),
                            prematureTerminal,
                            finalReasonCode,
                            runtimeUsage,
                            runtimeReservation);
            if (!ok) {
                log.warn(
                        "agent.run premature terminal CAS 失败 run={} target={}",
                        handle.run().runId(),
                        prematureTerminal);
            }
        }

        return new AgentRunResult(
                handle.run().runId(),
                requestId,
                finalStatus,
                accumulator.snapshot(),
                runtimeUsage,
                runtimeReservation,
                completedSteps,
                realToolCalls,
                replayedCalls,
                dedupHits,
                finalReasonCode,
                handle.run().version(),
                startedAt,
                Instant.now(clock));
    }

    private ScheduledExecutorService startHeartbeat(
            String runId, String ownerId, Duration ttl, AtomicBoolean leaseLost) {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "agent-lease-heartbeat-" + runId);
                            thread.setDaemon(true);
                            return thread;
                        });
        long interval = Math.max(1, Math.min(heartbeatSeconds, Math.max(1, ttl.toSeconds() / 2)));
        scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        if (!leaseService.heartbeat(runId, ownerId, ttl)) {
                            leaseLost.set(true);
                            log.error("agent.lease_lost run_id={}, owner_id={}", runId, ownerId);
                        }
                    } catch (Exception e) {
                        leaseLost.set(true);
                        log.error(
                                "agent.lease_heartbeat_failed run_id={}, owner_id={}, error={}",
                                runId, ownerId, e.getMessage());
                    }
                },
                interval,
                interval,
                TimeUnit.SECONDS);
        return scheduler;
    }

    private void ensureLeaseOwned(
            AtomicBoolean leaseLost, String runId, String ownerId, Duration ttl) {
        if (leaseLost.get() || !leaseService.heartbeat(runId, ownerId, ttl)) {
            leaseLost.set(true);
            throw new AgentLeaseLostException("Agent Run 执行期间丢失租约: " + runId);
        }
    }

    private static String executionOwnerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
    }

    public static final class AgentLeaseUnavailableException extends RuntimeException {
        public AgentLeaseUnavailableException(String message) { super(message); }
    }

    public static final class AgentLeaseLostException extends RuntimeException {
        public AgentLeaseLostException(String message) { super(message); }
    }

    // ─── 内部 ────────────────────────────────────────────

    private static boolean isExpired(Clock clock, Instant deadline) {
        return deadline != null && Instant.now(clock).isAfter(deadline);
    }

    private ToolResult<? extends ToolOutput> invokeToolOutsideTransaction(
            AgentToolStep planStep,
            AgentRunHandle handle,
            AgentExecutionPolicy policy,
            String requestId) {
        // Revision §11.4 — 这一行 <b>不</b>在 @Transactional; Coordinator 方法均已 REQUIRES_NEW 短事务,
        // Tool 调用本身位于普通线程上下文, 与所有事务边界隔离。Spring IT 实测见 AgentRunExecutorToolTxIT。
        return toolExecutor.execute(
                planStep.toolName(),
                planStep.toolVersion(),
                planStep.input(),
                new ToolExecutor.ToolCallRequest(
                        requestId,
                        handle.run().runId(),
                        policy.deadline(),
                        handle.run().indexVersion()));
    }

    private List<Evidence> extractEvidence(ToolResult<? extends ToolOutput> r) {
        if (r != null && r.output() instanceof EvidenceListOutput elo) {
            return elo.evidences();
        }
        return List.of();
    }

    private static boolean isReplayed(ToolResult<? extends ToolOutput> r) {
        return r != null && Boolean.TRUE.equals(r.metadata().get("harness_replayed"));
    }

    private static boolean isDeduplicated(ToolResult<? extends ToolOutput> r) {
        return r != null && Boolean.TRUE.equals(r.metadata().get("deduplicated"));
    }

    private StepSettlement buildSettlement(
            ToolResult<? extends ToolOutput> r,
            AgentStepStatus terminal,
            String errorCode,
            boolean replayed,
            boolean deduplicated) {
        if (replayed) return StepSettlement.replay(terminal, errorCode);
        if (deduplicated) return StepSettlement.dedup(terminal, errorCode);
        return StepSettlement.realTool(terminal, errorCode, 0, 0, java.math.BigDecimal.ZERO);
    }

    private record DependencyCheckResult(boolean failed, List<String> missing) {}

    private DependencyCheckResult checkDependencies(
            AgentToolStep step, Set<String> succeededSteps) {
        List<String> missing = new ArrayList<>();
        for (String dep : step.dependsOn()) {
            if (!succeededSteps.contains(dep)) {
                missing.add(dep);
            }
        }
        return new DependencyCheckResult(!missing.isEmpty(), missing);
    }

    private AgentStepRecord transitionStepTerminal(
            AgentStepRecord step, AgentRunHandle handle, AgentStepStatus target, String errorCode) {
        boolean ok =
                coordinator.transitionStep(
                        handle.run().runId(),
                        step.stepId(),
                        step.version(),
                        Set.of(
                                AgentStepStatus.PENDING,
                                AgentStepStatus.RESERVED,
                                AgentStepStatus.RUNNING,
                                AgentStepStatus.FAILED_RETRYABLE),
                        target,
                        AgentStepUpdate.empty());
        if (!ok) {
            log.warn(
                    "agent.step terminal cleanup CAS failed step={} target={}",
                    step.stepId(),
                    target);
        }
        // 返回新 step record (version+1 假定)
        return new AgentStepRecord(
                step.runId(),
                step.stepId(),
                step.stepSequence(),
                step.toolName(),
                step.toolVersion(),
                step.callId(),
                step.inputHash(),
                target,
                step.resultCount(),
                step.evidenceIds(),
                step.latencyMs(),
                errorCode,
                step.retryable(),
                step.replayed(),
                step.deduplicated(),
                step.startedAt(),
                step.completedAt(),
                step.createdAt(),
                step.updatedAt(),
                step.version() + 1);
    }

    private void cancelStepAndMarkPremature(AgentStepRecord step, AgentRunHandle handle) {
        if (step.status() != null && step.status().isTerminal()) return;
        transitionStepTerminal(step, handle, AgentStepStatus.CANCELLED, "CANCELLED");
    }

    /** Cleanup pass — 终结尚未终态的 step。 */
    private void cleanupNonTerminalSteps(AgentRunHandle handle, boolean cancelledByPremature) {
        for (AgentStepRecord s : handle.orderedSteps()) {
            AgentStepStatus st = s.status();
            if (st == null || !st.isTerminal()) {
                AgentStepStatus target =
                        cancelledByPremature
                                ? AgentStepStatus.CANCELLED
                                : AgentStepStatus.FAILED_TERMINAL;
                transitionStepTerminal(s, handle, target, "RUN_CLEANUP");
            }
        }
    }

    // Retry helpers — 有限次 CAS 重试 (Revision §4.6)
    private AgentPersistenceCoordinator.ReservationResult retryCas(
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

    private long retryCasLong(java.util.function.Supplier<Long> sup) {
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

    private AgentPersistenceCoordinator.SettlementResult retryCasSettle(
            java.util.function.Supplier<AgentPersistenceCoordinator.SettlementResult> sup) {
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
