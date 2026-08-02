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

    /** 按 content_hash 查(幂等命中场景)。 */
    Optional<ParseTask> findByContentHash(String contentHash);

    /** 按 documentId 查(回链查最新状态)。 */
    Optional<ParseTask> findByDocumentId(Long documentId);

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
}
