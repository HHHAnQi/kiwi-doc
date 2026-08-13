package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.GenerationCleanupRepository;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.GenerationCleanupEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.GenerationCleanupJpaRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaGenerationCleanupRepository implements GenerationCleanupRepository {
    private final GenerationCleanupJpaRepository jpa;

    @Override
    @Transactional
    public void enqueue(long documentId, int generation) {
        // INSERT IGNORE 在数据库侧完成幂等，不让唯一键异常污染外层 generation 切换事务。
        jpa.enqueueIfAbsent(documentId, generation);
    }

    @Override
    public List<Task> findDue(Instant now, int limit) {
        return jpa.findDue(now, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(e -> new Task(e.getId(), e.getDocumentId(), e.getGeneration(), e.getAttempts()))
                .toList();
    }

    @Override @Transactional
    public boolean claim(long id, Instant now, Instant leaseUntil) {
        return jpa.claim(id, now, leaseUntil) == 1;
    }

    @Override @Transactional
    public void markDone(long id) {
        jpa.findById(id).ifPresent(e -> {
            e.setStatus("DONE");
            e.setLeaseUntil(null);
            e.setUpdatedAt(Instant.now());
            jpa.save(e);
        });
    }

    @Override @Transactional
    public void markRetry(
            long id, int attempts, Instant nextAttemptAt, String error, boolean dead) {
        jpa.findById(id).ifPresent(e -> {
            e.setStatus(dead ? "DEAD" : "PENDING");
            e.setAttempts(attempts);
            e.setNextAttemptAt(nextAttemptAt);
            e.setLeaseUntil(null);
            e.setLastError(error == null ? null : error.substring(0, Math.min(1000, error.length())));
            e.setUpdatedAt(Instant.now());
            jpa.save(e);
        });
    }
}
