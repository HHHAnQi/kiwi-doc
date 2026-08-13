package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.GenerationCleanupEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface GenerationCleanupJpaRepository
        extends JpaRepository<GenerationCleanupEntity, Long> {
    @Modifying
    @Query(value = "INSERT IGNORE INTO ingestion_generation_cleanup "
            + "(document_id, generation, status, attempts, next_attempt_at, created_at, updated_at) "
            + "VALUES (:documentId, :generation, 'PENDING', 0, CURRENT_TIMESTAMP(6), "
            + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))", nativeQuery = true)
    int enqueueIfAbsent(
            @Param("documentId") long documentId, @Param("generation") int generation);

    @Query("SELECT j FROM GenerationCleanupEntity j WHERE j.status IN ('PENDING','RUNNING') "
            + "AND j.nextAttemptAt <= :now AND (j.leaseUntil IS NULL OR j.leaseUntil < :now) "
            + "ORDER BY j.id")
    List<GenerationCleanupEntity> findDue(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE GenerationCleanupEntity j SET j.status='RUNNING', j.leaseUntil=:leaseUntil, "
            + "j.updatedAt=:now WHERE j.id=:id AND j.status IN ('PENDING','RUNNING') "
            + "AND (j.leaseUntil IS NULL OR j.leaseUntil < :now)")
    int claim(@Param("id") long id, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);
}
