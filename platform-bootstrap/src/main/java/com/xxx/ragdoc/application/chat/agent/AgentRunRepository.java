package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PR-6a.2: agent_run 持久化 Port。
 *
 * <p>实现: {@code AgentRunRepositoryImpl} (infrastructure/persistence/jpa) 用 JPA Entity + @Modifying
 * CAS。
 *
 * <p>关键不变量:
 *
 * <ul>
 *   <li>{@link #create} 只接受 status=RECEIVED + version=0 的新记录
 *   <li>{@link #transition} 使用 CAS {@code WHERE version=? AND status IN (...)} 保证唯一终态
 *   <li>{@link #updateBudgetState} 不改 status, 只 CAS 更新 usage + reservation
 *   <li>终态不能被任何方法再次写入
 * </ul>
 */
public interface AgentRunRepository {

    /** 创建新 Run。runId 已存在 → DataIntegrityViolationException。 */
    AgentRunRecord create(AgentRunRecord run);

    Optional<AgentRunRecord> findByRunId(String runId);

    /** 按 tenantId 审计查询 (按 created_at 倒序, 限量)。 */
    List<AgentRunRecord> findByTenantId(String tenantId, int limit);

    /** 扫描长时间未更新的非终态 Run，供重启恢复守护使用。 */
    List<AgentRunRecord> findStaleNonTerminal(Instant updatedBefore, int limit);

    boolean claimLease(String runId, String ownerId, Instant now, Instant leaseUntil);

    boolean heartbeat(String runId, String ownerId, Instant now, Instant leaseUntil);

    void releaseLease(String runId, String ownerId);

    /**
     * P2-D5(A): 写入过程决策摘要(INITIAL_SUFFICIENT / REPLAN_SUFFICIENT / REPLAN_EXHAUSTED_FALLBACK /
     * REFUSED_CONFLICT / TOOL_FAILURE)。 语义: 只在为空时写入 — 不被后续终态(PLANNED_ANSWER_READY)覆盖。
     */
    void updateDecisionSummary(String runId, String decisionSummary);

    /** P2-D5(A): 读取过程决策摘要(run API 透出)。 */
    java.util.Optional<String> findDecisionSummary(String runId);

    /**
     * CAS 状态转换。
     *
     * @return true=成功 / false=version冲突 / status不匹配 / run不存在
     */
    boolean transition(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentRunStatus targetStatus,
            String terminalReasonCode,
            AgentUsage usage,
            AgentBudgetReservation reservation);

    /**
     * CAS 更新预算状态 (不改 status; 仅 usage + reservation)。
     *
     * <p>PR-6b BudgetManager 用此方法做原子预留。
     */
    boolean updateBudgetState(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentUsage usage,
            AgentBudgetReservation reservation);

    /** CAS 更新 evidence 摘要 (只存 evidenceIds + count, 不存正文)。 */
    boolean updateEvidenceSummary(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            List<String> evidenceIds,
            int evidenceCount);

    /**
     * PR-6b.1: 结算合并 CAS — 一次 CAS 同时推进 usage + reservation + evidenceIds + count。
     *
     * <p>Revision §4 要求: settleStep 内 Run CAS 不得用 updateBudgetState + updateEvidenceSummary 两次串行,
     * 因为第二次 CAS 必须用第一次成功后的新版本, 易写错且产生版本双推进。这里以<b>一次</b> CAS 同时改四组字段, version+1。
     *
     * @return true=成功 / false=version冲突 / status不匹配 / run不存在
     */
    boolean settleRunStep(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentUsage usage,
            AgentBudgetReservation reservation,
            List<String> evidenceIds,
            int evidenceCount);
}
