package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.time.Instant;
import java.util.List;

/**
 * PR-6b.3 / EMS-PR6 §12: Agent Run 执行最终结果。
 *
 * <p>关键安全约束:
 *
 * <ul>
 *   <li>{@link #evidence} 只在<b>内存</b>结果中, 由 Executor 持有, 不持久化到 agent_run / agent_step。
 *       数据库 agent_run 只保存 evidenceIds + count (见 AgentRunRepository.settleRunStep 与
 *       AgentPersistenceCoordinator.settleStep)。
 *   <li>本结果<b>不</b>直接返回给普通外部 API; 普通 Chat 仍走 422 (AGENTIC 暂未启用)。
 *   <li>终态结果与数据库状态一致 — Executor 写完 Run 终态后构造本 record。
 * </ul>
 */
public record AgentRunResult(
        String runId,
        String requestId,
        AgentRunStatus status,
        List<Evidence> evidence,
        AgentUsage usage,
        AgentBudgetReservation reservation,
        int completedSteps,
        int realToolCalls,
        int replayedCalls,
        int dedupHits,
        String terminalReasonCode,
        Instant startedAt,
        Instant completedAt) {

    public AgentRunResult {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (status == null) status = AgentRunStatus.SYSTEM_FAILED;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (usage == null) usage = AgentUsage.zero();
        if (reservation == null) reservation = AgentBudgetReservation.zero();
    }
}
