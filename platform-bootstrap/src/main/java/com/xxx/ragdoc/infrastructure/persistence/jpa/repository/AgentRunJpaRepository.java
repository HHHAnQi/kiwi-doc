package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentRunEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** PR-6a.2: agent_run Spring Data JPA Repository (CAS 接口)。 */
@Repository
public interface AgentRunJpaRepository extends JpaRepository<AgentRunEntity, String> {

    List<AgentRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * CAS state transition: 受影响行数 1=成功 / 0=冲突或不匹配。
     *
     * <p>动态 IN (:statuses): Spring Data JPA 绑定 Collection → MySQL `status IN (?,?,?...)`。
     */
    @Modifying
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
    @Modifying
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
    @Modifying
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
}
