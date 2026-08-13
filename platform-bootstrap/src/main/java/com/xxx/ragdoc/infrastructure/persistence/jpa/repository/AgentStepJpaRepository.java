package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentStepEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** PR-6a.2: agent_step Spring Data JPA Repository (CAS 接口)。 */
public interface AgentStepJpaRepository extends JpaRepository<AgentStepEntity, Long> {

    Optional<AgentStepEntity> findByRunIdAndStepId(String runId, String stepId);

    List<AgentStepEntity> findByRunIdOrderByStepSequenceAsc(String runId);

    @Modifying
    @Query(
            "UPDATE AgentStepEntity e SET "
                    + "e.status = :target, "
                    + "e.callId = COALESCE(:callId, e.callId), "
                    + "e.resultCount = COALESCE(:resultCount, e.resultCount), "
                    + "e.evidenceIdsJson = COALESCE(:evidenceIdsJson, e.evidenceIdsJson), "
                    + "e.outputSnapshot = CASE WHEN e.recoverable=true "
                    + "AND :evidenceIdsJson IS NOT NULL THEN :evidenceIdsJson "
                    + "ELSE e.outputSnapshot END, "
                    + "e.latencyMs = COALESCE(:latencyMs, e.latencyMs), "
                    + "e.errorCode = COALESCE(:errorCode, e.errorCode), "
                    + "e.retryable = COALESCE(:retryable, e.retryable), "
                    + "e.replayed = COALESCE(:replayed, e.replayed), "
                    + "e.deduplicated = COALESCE(:deduplicated, e.deduplicated), "
                    + "e.startedAt = COALESCE(:startedAt, e.startedAt), "
                    + "e.completedAt = COALESCE(:completedAt, e.completedAt), "
                    + "e.updatedAt = CURRENT_TIMESTAMP, "
                    + "e.version = e.version + 1 "
                    + "WHERE e.runId = :runId "
                    + "AND e.stepId = :stepId "
                    + "AND e.version = :expectedVersion "
                    + "AND e.status IN :expectedStatuses")
    int transition(
            @Param("runId") String runId,
            @Param("stepId") String stepId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatuses") Collection<String> expectedStatuses,
            @Param("target") String target,
            @Param("callId") String callId,
            @Param("resultCount") Integer resultCount,
            @Param("evidenceIdsJson") String evidenceIdsJson,
            @Param("latencyMs") Long latencyMs,
            @Param("errorCode") String errorCode,
            @Param("retryable") Boolean retryable,
            @Param("replayed") Boolean replayed,
            @Param("deduplicated") Boolean deduplicated,
            @Param("startedAt") java.time.Instant startedAt,
            @Param("completedAt") java.time.Instant completedAt);
}
