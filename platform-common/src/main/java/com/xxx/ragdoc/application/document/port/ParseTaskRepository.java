package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * parse_tasks 表仓储端口(V3 parser-service 拆分, spec §3)。
 *
 * <p>共享于 platform-common: chat-app(DocumentUploadService 创建 PENDING task) 与 parser-service (worker
 * pull / 状态迁移 / 续点 flush) 同时依赖。两端注入同一接口,各自的 JPA 实现藏于自己模块。
 *
 * <p>状态迁移 invariant(spec §3.3) 由 {@code ParseTaskService} 守护, 本端口只承担 CRDU, 不仲裁迁移合法性。
 */
public interface ParseTaskRepository {

    /**
     * INSERT 一条 PENDING task(spec §3.3 第一行)。
     *
     * <p>幂等: content_hash 唯一索引由 DB 守护, 重复 INSERT 抛 DataIntegrityViolationException,
     * 调用方(DocumentUploadService) 捕获后视为 idempotent_hit 回查原 task。
     */
    ParseTask save(ParseTask task);

    /** 按 id 查单条。 */
    Optional<ParseTask> findById(Long id);

    /** 按 documentId 查最新 generation。 */
    Optional<ParseTask> findByDocumentId(Long documentId);

    default Optional<ParseTask> findByDocumentIdAndGeneration(Long documentId, int generation) {
        return findByDocumentId(documentId).filter(t -> t.generation() == generation);
    }

    default int nextGeneration(Long documentId) {
        return findByDocumentId(documentId).map(t -> t.generation() + 1).orElse(1);
    }

    /** 按消息中的 taskId 原子执行 PENDING→RUNNING；重复消息只能有一个消费者成功。 */
    default Optional<ParseTask> leaseById(
            Long taskId, String leasedBy, Instant leaseUntil, Instant now) {
        Optional<ParseTask> current = findById(taskId);
        if (current.isEmpty()) return Optional.empty();
        ParseTask task = current.get();
        if (task.status() != ParseTaskStatus.PENDING || task.visibleAt().isAfter(now)) {
            return Optional.empty();
        }
        ParseTask leased =
                task.withExecutionState(
                        ParseTaskStatus.RUNNING, task.retryCount(), task.chunksWritten(),
                        task.chunkSeqOffset(), task.errorMessage(), task.errorClass(),
                        task.attempts(), leaseUntil, leasedBy, now);
        update(leased);
        return Optional.of(leased);
    }

    /** 原子抢占一批待投递/租约过期的任务；多实例中只有抢占成功者可发送。 */
    default List<ParseTask> claimDueForDelivery(
            String leasedBy, Instant now, Instant leaseUntil, int limit) {
        return findDueForDelivery(now, limit);
    }

    /** MQ broker 确认成功后记录投递完成，Relay 不再重复发送同一轮任务。 */
    default void markDeliverySucceeded(Long taskId, Instant now) {}

    /** MQ 发送失败；达到上限后进入 DEAD，停止自动投递。 */
    default void markDeliveryFailed(
            Long taskId, Instant nextAttemptAt, String error, Instant now, int maxAttempts) {}

    /** 运维人工重放 DEAD 消息；仅允许 DEAD → PENDING。 */
    default boolean replayDeadDelivery(Long taskId, Instant now) { return false; }

    /**
     * 原子 lease: 抢占一条 PENDING + visible_at ≤ now 的 task。
     *
     * <p>SQL 语义: {@code UPDATE parse_tasks SET status='RUNNING', leased_by=?, visible_at=? WHERE id
     * IN (SELECT id FROM ... WHERE status='PENDING' AND visible_at<=? LIMIT 1)}. 实现需保证原子(行锁 + LIMIT
     * 1), 返回被 lease 的 task; 无可抢 task 返回 empty。
     *
     * @param leasedBy worker 标识(hostname+pid)
     * @param leaseUntil RUNNING lease 到期时间(心跳 job 据此回收 zombie worker)
     */
    Optional<ParseTask> leaseNextPending(String leasedBy, Instant leaseUntil, Instant now);

    /**
     * 心跳回收: 把 visible_at &lt; now 的 RUNNING 回滚为 PENDING(spec §3.3 第 3 行)。
     *
     * @return 被回收的 task 数(用于日志/告警)
     */
    int reapExpiredRunning(Instant now);

    /**
     * 显式状态迁移(由 ParseTaskService 仲裁后调用)。 update 字段: status / retry_count / chunks_written /
     * chunk_seq_offset / error_* / leased_by / visible_at / attempts。
     */
    void update(ParseTask task);

    /** 找出过期但仍 FAILED(redelivery delay 到点) → 回 PENDING 的候选; 实现也可合并进 leaseNextPending。 */
    List<ParseTask> findDueRetry(Instant now, ParseTaskStatus status);

    /** 批量获取需要重新投递的持久化任务；parse_tasks 的 PENDING 行即可靠投递账本。 */
    default List<ParseTask> findDueForDelivery(Instant now, int limit) {
        return findDueRetry(now, ParseTaskStatus.PENDING).stream().limit(limit).toList();
    }
}
