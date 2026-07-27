package com.xxx.ragdoc.domain.document.event;

import java.time.Instant;

/**
 * Document 解析成功事件。
 * V1 不发布(单体直接调用);V3 由 parser-service 发布,rag-service 监听。
 */
public record DocumentParsedEvent(
        Long documentId,
        String tenantId,
        int chunkCount,
        Instant occurredAt
) {
    public DocumentParsedEvent {
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 不能为负");
        }
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }
}
