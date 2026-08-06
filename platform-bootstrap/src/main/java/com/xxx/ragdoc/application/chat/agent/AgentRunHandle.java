package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import java.time.Clock;
import java.util.List;

/**
 * PR-6b.3: 单可执行 Run 的句柄 (Executor 内部传递; 非持久化 record)。
 *
 * @param run                 最新 Run snapshot (含 version)
 * @param orderedSteps        按拓扑序的 PENDING steps (含 version)
 * @param plan                原始 Plan
 * @param policy              服务端构造的执行策略
 * @param deadline            Run 全局截止时间 (来自 policy.deadline())
 * @param cancellation        CancellationToken 视图
 * @param tenantId            Principal.tenantId ( resale)
 * @param clock               时间注入 (Revision §10.3 — 不散落 Instant.now())
 */
public record AgentRunHandle(
        AgentRunRecord run,
        List<AgentStepRecord> orderedSteps,
        DeterministicExecutionPlan plan,
        AgentExecutionPolicy policy,
        java.time.Instant deadline,
        CancellationTokenSource.CancellationToken cancellation,
        String tenantId,
        Clock clock) {

    public AgentRunHandle {
        if (run == null) throw new IllegalArgumentException("run");
        orderedSteps = List.copyOf(orderedSteps);
        if (plan == null) throw new IllegalArgumentException("plan");
        if (policy == null) throw new IllegalArgumentException("policy");
        if (deadline == null) deadline = java.time.Instant.MAX;
        if (cancellation == null) cancellation = CancellationTokenSource.CancellationToken.never();
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId");
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    static AgentRunHandle from(InitializedRun init,
                                DeterministicExecutionPlan plan,
                                AgentExecutionPolicy policy,
                                CancellationTokenSource.CancellationToken cancellation,
                                String tenantId,
                                Clock clock) {
        return new AgentRunHandle(
                init.run(), init.steps(), plan, policy, policy.deadline(),
                cancellation, tenantId, clock);
    }

    /** 用新 Run record 替换内部 snapshot (Executor 每次写完 CAS 后推进)。 */
    AgentRunHandle withRunVersion(long newVersion) {
        AgentRunRecord updated = new AgentRunRecord(
                run.runId(), run.requestId(), run.tenantId(), run.userId(), run.strategy(),
                run.status(), run.planId(), run.planVersion(), run.planHash(), run.planJson(),
                run.budget(), run.reservation(), run.usage(), run.evidenceIds(), run.evidenceCount(),
                run.terminalReasonCode(), run.routerVersion(), run.toolsetVersion(),
                run.indexVersion(), run.harnessMode(), run.createdAt(), run.updatedAt(),
                newVersion);
        return new AgentRunHandle(
                updated, orderedSteps, plan, policy, deadline, cancellation, tenantId, clock);
    }
}
