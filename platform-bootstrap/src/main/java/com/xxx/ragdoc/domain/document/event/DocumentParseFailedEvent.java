package com.xxx.ragdoc.domain.document.event;

import java.time.Instant;

/** Document 解析失败事件。V3 由 parser-service 发布,触发死信或人工介入。 */
public record DocumentParseFailedEvent(
        Long documentId, String tenantId, String errorMessage, int retryCount, Instant occurredAt) {
    public DocumentParseFailedEvent {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage 不能为空");
        }
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }
}
