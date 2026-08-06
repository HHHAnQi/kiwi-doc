package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PR-6 / EMS-PR6 §4.8: Agent Run 聚合根 (不可变 transition)。
 *
 * <p>这是 PR-6 的最小 SNAPSHOT: 每次转换构造新 record; concurrent 状态化通过持久化层的 {@code version} (乐观锁)
 * 保证单一终态 (AgentRunPersistenceService)。
 *
 * <p>本字段集 PR-6 必要; version 是持久化用的乐观锁列, 与 AgentState 的 stateVersion 二者一致。
 */
public record AgentState(
        String runId,
        String requestId,
        String tenantId,
        String userId,
        ExecutionStrategy strategy,
        AgentRunStatus status,
        DeterministicExecutionPlan plan,
        List<String> completedStepIds,
        List<Evidence> evidence,
        AgentBudget budget,
        AgentUsage usage,
        AgentExecutionPolicy executionPolicy,
        String terminalReasonCode,
        Map<String, String> versions, // routerVersion, toolsetVersion, indexVersion, promptVersion, embeddingVersion
        Instant createdAt,
        Instant updatedAt,
        long stateVersion) {

    public AgentState {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 必填");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId 必填");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 必填");
        if (status == null) status = AgentRunStatus.RECEIVED;
        completedStepIds = completedStepIds == null ? List.of() : List.copyOf(completedStepIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (budget == null) budget = AgentBudget.pr6Default();
        if (usage == null) usage = AgentUsage.zero();
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    /** 不可变 transition: 返回带新 status 与 stateVersion+1 的副本。 */
    public AgentState transitionTo(AgentRunStatus newStatus, String reasonCode) {
        return new AgentState(
                runId, requestId, tenantId, userId, strategy, newStatus,
                plan,
                completedStepIds, evidence, budget, usage, executionPolicy,
                reasonCode,
                versions,
                createdAt,
                Instant.now(),
                stateVersion + 1);
    }

    public AgentState withEvidence(List<Evidence> newEvidence) {
        return new AgentState(
                runId, requestId, tenantId, userId, strategy, status, plan,
                completedStepIds, List.copyOf(newEvidence),
                budget, usage, executionPolicy, terminalReasonCode,
                versions, createdAt, Instant.now(), stateVersion + 1);
    }

    public AgentState withCompletedStep(String stepId, AgentUsage newUsage) {
        List<String> newCompleted = new java.util.ArrayList<>(completedStepIds);
        if (!newCompleted.contains(stepId)) newCompleted.add(stepId);
        return new AgentState(
                runId, requestId, tenantId, userId, strategy, status, plan,
                List.copyOf(newCompleted), evidence,
                budget, newUsage, executionPolicy, terminalReasonCode,
                versions, createdAt, Instant.now(), stateVersion + 1);
    }
}
