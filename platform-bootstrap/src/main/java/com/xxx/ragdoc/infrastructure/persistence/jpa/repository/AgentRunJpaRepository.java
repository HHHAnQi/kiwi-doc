package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentRunEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** PR-6a.2: agent_run Spring Data JPA Repository (CAS 接口)。 */
@Repository
public interface AgentRunJpaRepository extends JpaRepository<AgentRunEntity, String> {

    List<AgentRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Query(
            "SELECT e FROM AgentRunEntity e WHERE e.updatedAt < :updatedBefore "
                    + "AND e.status IN :statuses ORDER BY e.updatedAt ASC")
    List<AgentRunEntity> findStaleNonTerminal(
            @Param("updatedBefore") Instant updatedBefore,
            @Param("statuses") Collection<String> statuses,
            org.springframework.data.domain.Pageable pageable);

    /**
     * CAS state transition: 受影响行数 1=成功 / 0=冲突或不匹配。
     *
     * <p>动态 IN (:statuses): Spring Data JPA 绑定 Collection → MySQL `status IN (?,?,?...)`。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET e.status = :target, "
                    + "e.terminalReasonCode = :reasonCode, "
                    + "e.usageJson = :usageJson, "
                    + "e.reservationJson = :reservationJson, "
                    + "e.updatedAt = CURRENT_TIMESTAMP, "
                    + "e.version = e.version + 1 "
                    + "WHERE e.runId = :runId "
                    + "AND e.version = :expectedVersion "
                    + "AND e.status IN :expectedStatuses")
    int transition(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatuses") Collection<String> expectedStatuses,
            @Param("target") String target,
            @Param("reasonCode") String reasonCode,
            @Param("usageJson") String usageJson,
            @Param("reservationJson") String reservationJson);

    /** CAS 更新预算状态: 不改 status, 仅 usage+reservation。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET "
                    + "e.usageJson = :usageJson, "
                    + "e.reservationJson = :reservationJson, "
                    + "e.updatedAt = CURRENT_TIMESTAMP, "
                    + "e.version = e.version + 1 "
                    + "WHERE e.runId = :runId "
                    + "AND e.version = :expectedVersion "
                    + "AND e.status IN :expectedStatuses")
    int updateBudgetState(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatuses") Collection<String> expectedStatuses,
            @Param("usageJson") String usageJson,
            @Param("reservationJson") String reservationJson);

    /** CAS 更新 evidence 摘要。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET "
                    + "e.evidenceIdsJson = :evidenceIdsJson, "
                    + "e.evidenceCount = :evidenceCount, "
                    + "e.version = e.version + 1 "
                    + "WHERE e.runId = :runId "
                    + "AND e.version = :expectedVersion "
                    + "AND e.status IN :expectedStatuses")
    int updateEvidenceSummary(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatuses") Collection<String> expectedStatuses,
            @Param("evidenceIdsJson") String evidenceIdsJson,
            @Param("evidenceCount") int evidenceCount);

    /**
     * PR-6b.1: 结算合并 CAS — 一次 UPDATE 同时改 usage/reservation/evidenceIds/evidenceCount + version+1。
     *
     * <p>避免 settleStep 内两次串行 CAS 出现版本错位 (Revision §4)。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET "
                    + "e.usageJson = :usageJson, "
                    + "e.reservationJson = :reservationJson, "
                    + "e.evidenceIdsJson = :evidenceIdsJson, "
                    + "e.evidenceCount = :evidenceCount, "
                    + "e.updatedAt = CURRENT_TIMESTAMP, "
                    + "e.version = e.version + 1 "
                    + "WHERE e.runId = :runId "
                    + "AND e.version = :expectedVersion "
                    + "AND e.status IN :expectedStatuses")
    int settleRunStep(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatuses") Collection<String> expectedStatuses,
            @Param("usageJson") String usageJson,
            @Param("reservationJson") String reservationJson,
            @Param("evidenceIdsJson") String evidenceIdsJson,
            @Param("evidenceCount") int evidenceCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET e.ownerId=:ownerId, e.leaseUntil=:leaseUntil, "
                    + "e.heartbeatAt=:now, e.updatedAt=:now "
                    + "WHERE e.runId=:runId AND e.status IN :statuses AND "
                    + "(e.ownerId IS NULL OR e.leaseUntil < :now OR e.ownerId=:ownerId)")
    int claimLease(
            @Param("runId") String runId,
            @Param("ownerId") String ownerId,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now,
            @Param("statuses") Collection<String> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET e.leaseUntil=:leaseUntil, e.heartbeatAt=:now, "
                    + "e.updatedAt=:now WHERE e.runId=:runId AND e.ownerId=:ownerId "
                    + "AND e.status IN :statuses")
    int heartbeat(
            @Param("runId") String runId,
            @Param("ownerId") String ownerId,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now,
            @Param("statuses") Collection<String> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET e.ownerId=NULL, e.leaseUntil=NULL "
                    + "WHERE e.runId=:runId AND e.ownerId=:ownerId")
    int releaseLease(@Param("runId") String runId, @Param("ownerId") String ownerId);

    /** P2-D5(A): 写入过程决策摘要(只在为空时写 — "一经写入不再覆盖"语义)。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE AgentRunEntity e SET e.decisionSummary=:summary, "
                    + "e.updatedAt=CURRENT_TIMESTAMP "
                    + "WHERE e.runId=:runId AND e.decisionSummary IS NULL")
    int updateDecisionSummary(@Param("runId") String runId, @Param("summary") String summary);

    /** P2-D5(A): 读取过程决策摘要(run API 透出用)。 */
    @Query("SELECT e.decisionSummary FROM AgentRunEntity e WHERE e.runId=:runId")
    Optional<String> findDecisionSummary(@Param("runId") String runId);
}
