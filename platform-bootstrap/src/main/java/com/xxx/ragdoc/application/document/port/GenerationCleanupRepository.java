package com.xxx.ragdoc.application.document.port;

import java.time.Instant;
import java.util.List;

/** 代际切换后的旧索引清理任务；入队与 active_generation 切换处于同一个 MySQL 事务。 */
public interface GenerationCleanupRepository {
    void enqueue(long documentId, int generation);

    List<Task> findDue(Instant now, int limit);

    boolean claim(long id, Instant now, Instant leaseUntil);

    void markDone(long id);

    void markRetry(long id, int attempts, Instant nextAttemptAt, String error, boolean dead);

    record Task(long id, long documentId, int generation, int attempts) {}
}
