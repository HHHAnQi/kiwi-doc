package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.List;

/**
 * PR-6a.2: agent_run 表的应用层映射 record。
 *
 * <p>与 {@link AgentState} 聚合根的区别:
 *
 * <ul>
 *   <li>AgentState 持有完整 {@code List<Evidence>} (运行时需要)
 *   <li>AgentRunRecord **只** 持有 {@code evidenceIds} + {@code evidenceCount}, 不含 Evidence 正文
 *   <li>AgentRunRecord 持有 budget/reservation/usage 三个 JSON 可序列化对象
 * </ul>
 *
 * <p>持久化的 Evidence 正文由 PR-1 chat_traces.evidence_snapshot 管, 不重复存。
 */
public record AgentRunRecord(
        String runId,
        String requestId,
        String tenantId,
        String userId,
        String strategy,
        AgentRunStatus status,
        String planId,
        String planVersion,
        String planHash,
        String planJson,
        AgentBudget budget,
        AgentBudgetReservation reservation,
        AgentUsage usage,
        List<String> evidenceIds,
        int evidenceCount,
        String terminalReasonCode,
        String routerVersion,
        String toolsetVersion,
        String indexVersion,
        String harnessMode,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public AgentRunRecord {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId");
        if (status == null) status = AgentRunStatus.RECEIVED;
        if (budget == null) budget = AgentBudget.pr6Default();
        if (reservation == null) reservation = AgentBudgetReservation.zero();
        if (usage == null) usage = AgentUsage.zero();
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (harnessMode == null) harnessMode = "LIVE";
    }
}
