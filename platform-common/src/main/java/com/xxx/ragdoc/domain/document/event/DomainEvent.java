package com.xxx.ragdoc.domain.document.event;

import java.time.Instant;

/**
 * 领域事件基类。V1 不发布(单体内部直接调用);V3 引入 RocketMQ 时由各服务发布。 所有事件必须保存 {@code documentId} 与 {@code
 * occurredAt},便于回放与审计。
 */
public record DomainEvent(Long documentId, String tenantId, Instant occurredAt) {
    public DomainEvent {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("documentId 非法");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }
}
