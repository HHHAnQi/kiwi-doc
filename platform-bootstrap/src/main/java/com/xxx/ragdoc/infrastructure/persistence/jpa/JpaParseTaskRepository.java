package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ParseTaskEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.ParseTaskJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ParseTaskRepository} 端口的 JPA 适配实现(V3 parser-service 拆分)。
 *
 * <p>{@link #leaseNextPending} 是 worker 抢占心跳核心: PESSIMISTIC_WRITE 行锁查候选 + markRunning 单条 update,
 * 全过程包在 REQUIRES_NEW 短事务内, 防 worker 并发抢同一 task。
 *
 * <p>占位说明: chat-app 默认 rag.parser.mode=sync 时本 bean 仍被装配(直接调 markRunning 等)， async 路径靠
 * ParseTaskProducer + parser-service 远程消费。chat-app 自己不开 worker。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaParseTaskRepository implements ParseTaskRepository {

    private final ParseTaskJpaRepository jpa;
    private final ParseTaskMapper mapper;

    @Override
    @Transactional
    public ParseTask save(ParseTask task) {
        ParseTaskEntity saved = jpa.save(mapper.toEntity(task));
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParseTask> findById(Long id) {
        return jpa.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParseTask> findByDocumentId(Long documentId) {
        return jpa.findFirstByDocumentIdOrderByGenerationDesc(documentId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ParseTask> findByDocumentIdAndGeneration(Long documentId, int generation) {
        return jpa.findByDocumentIdAndGeneration(documentId, generation).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public int nextGeneration(Long documentId) {
        return jpa.maxGeneration(documentId) + 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ParseTask> leaseById(
            Long taskId, String leasedBy, Instant leaseUntil, Instant now) {
        int affected = jpa.markRunningById(taskId, leasedBy, leaseUntil, now);
        if (affected == 0) return Optional.empty();
        return jpa.findById(taskId).map(mapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeliverySucceeded(Long taskId, Instant now) {
        jpa.markDeliverySucceeded(taskId, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeliveryFailed(
            Long taskId, Instant nextAttemptAt, String error, Instant now, int maxAttempts) {
        String safeError = error == null ? null : error.substring(0, Math.min(error.length(), 512));
        jpa.markDeliveryFailed(taskId, nextAttemptAt, safeError, now, maxAttempts);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replayDeadDelivery(Long taskId, Instant now) {
        return jpa.replayDeadDelivery(taskId, now) == 1;
    }

    /**
     * 原子 lease 两步:
     *
     * <ol>
     *   <li>findLeaseCandidates(PESSIMISTIC_WRITE 行锁) limit 1 选一条
     *   <li>markRunning id = candidate.id 状态迁移(若另一线程已抢走返回 0)
     * </ol>
     *
     * <p>整段在 REQUIRES_NEW 短事务里, 行锁持有仅在事务期, commit 后释放。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ParseTask> leaseNextPending(String leasedBy, Instant leaseUntil, Instant now) {
        List<ParseTaskEntity> candidates = jpa.findLeaseCandidates(now);
        if (candidates.isEmpty()) return Optional.empty();
        ParseTaskEntity candidate = candidates.get(0);
        int affected = jpa.markRunning(candidate.getId(), leasedBy, leaseUntil, now);
        if (affected == 0) {
            log.debug("parse_task.lease_lost task_id={} (race)", candidate.getId());
            return Optional.empty();
        }
        // 标记 RUNNING 后重新查回 leasedBy / leaseUntil 已写入的最新行
        return jpa.findById(candidate.getId()).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public int reapExpiredRunning(Instant now) {
        return jpa.reapExpiredRunning(now);
    }

    @Override
    @Transactional
    public void update(ParseTask task) {
        ParseTaskEntity entity = mapper.toEntity(task);
        // update 走 save(merge) —— id 非 null 时 Hibernate 用 SELECT + UPDATE
        jpa.save(entity);
        // 执行失败进入延迟 PENDING 重试时，必须重新打开消息投递；否则 delivery=SENT 会让 Relay 永久跳过。
        if (task.status() == ParseTaskStatus.PENDING && task.id() != null) {
            jpa.resetDeliveryPending(task.id(), task.visibleAt(), task.updatedAt());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParseTask> findDueRetry(Instant now, ParseTaskStatus status) {
        return jpa
                .findDue(now, status.name(), org.springframework.data.domain.PageRequest.of(0, 100))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParseTask> findDueForDelivery(Instant now, int limit) {
        return jpa
                .findDueDelivery(
                        now, org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ParseTask> claimDueForDelivery(
            String leasedBy, Instant now, Instant leaseUntil, int limit) {
        var candidates =
                jpa.findDeliveryClaimCandidates(
                        now, org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
        java.util.List<ParseTask> claimed = new java.util.ArrayList<>(candidates.size());
        for (var candidate : candidates) {
            if (jpa.claimDelivery(candidate.getId(), leasedBy, leaseUntil, now) == 1) {
                jpa.findById(candidate.getId()).map(mapper::toDomain).ifPresent(claimed::add);
            }
        }
        return claimed;
    }
}
