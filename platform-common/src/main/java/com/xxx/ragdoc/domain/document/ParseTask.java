package com.xxx.ragdoc.domain.document;

import java.time.Instant;
import java.util.List;

/**
 * parser-service 任务聚合根(对应 parse_tasks 表一行)。
 *
 * <p>共享于 platform-common: chat-app(DocumentUploadService INSERT) 与 parser-service(worker 消费)
 * 共用同一类型。
 *
 * <p>不可变 record(创建后字段不可改, 状态迁移通过 ParseTaskService 写新对象回 repo)。
 *
 * <p>幂等 key = {@link #contentHash}(SHA-256 of 原始文件), 与 documents.content_hash 共享 —— 同文件重复上传唯一索引拒绝,
 * 直接返原 doc。
 */
public record ParseTask(
        Long id,
        Long documentId,
        int generation,
        TriggerType triggerType,
        Long supersedesTaskId,
        String contentHash,
        ParseTaskStatus status,
        int retryCount,
        int maxRetries,
        int chunksWritten,
        int chunkSeqOffset,
        String errorMessage,
        String errorClass,
        List<Attempt> attempts,
        Instant visibleAt,
        String leasedBy,
        DeliveryStatus deliveryStatus,
        int deliveryAttempts,
        Instant nextDeliveryAt,
        String deliveryError,
        Instant createdAt,
        Instant updatedAt) {

    public ParseTask(
            Long id,
            Long documentId,
            String contentHash,
            ParseTaskStatus status,
            int retryCount,
            int maxRetries,
            int chunksWritten,
            int chunkSeqOffset,
            String errorMessage,
            String errorClass,
            List<Attempt> attempts,
            Instant visibleAt,
            String leasedBy,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                documentId,
                1,
                TriggerType.UPLOAD,
                null,
                contentHash,
                status,
                retryCount,
                maxRetries,
                chunksWritten,
                chunkSeqOffset,
                errorMessage,
                errorClass,
                attempts,
                visibleAt,
                leasedBy,
                DeliveryStatus.PENDING,
                0,
                visibleAt,
                null,
                createdAt,
                updatedAt);
    }

    public enum DeliveryStatus {
        PENDING,
        SENDING,
        SENT,
        DEAD
    }

    public enum TriggerType {
        UPLOAD,
        RETRY,
        REBUILD,
        RECONCILE
    }

    public ParseTask withExecutionState(
            ParseTaskStatus newStatus,
            int newRetryCount,
            int newChunksWritten,
            int newChunkSeqOffset,
            String newErrorMessage,
            String newErrorClass,
            List<Attempt> newAttempts,
            Instant newVisibleAt,
            String newLeasedBy,
            Instant newUpdatedAt) {
        return new ParseTask(
                id,
                documentId,
                generation,
                triggerType,
                supersedesTaskId,
                contentHash,
                newStatus,
                newRetryCount,
                maxRetries,
                newChunksWritten,
                newChunkSeqOffset,
                newErrorMessage,
                newErrorClass,
                newAttempts,
                newVisibleAt,
                newLeasedBy,
                deliveryStatus,
                deliveryAttempts,
                nextDeliveryAt,
                deliveryError,
                createdAt,
                newUpdatedAt);
    }

    /** 单次 attempt 历史(V3 commit 3 dead letter 分析用)。 */
    public record Attempt(Instant at, long durationMs, String errorClass, String errorMessage) {}

    /** 是否可视: worker pull 时 visible_at ≤ now。 */
    public boolean isVisible(Instant now) {
        return !visibleAt.isAfter(now);
    }

    /** 是否还能重试(retry_count < max_retries)。 */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }
}
