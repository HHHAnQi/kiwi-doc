package com.xxx.ragdoc.infrastructure.mq;

import java.time.Instant;

/**
 * parse-task-submit 消息体(spec §2.2 chat-app → parser-service).
 *
 * <p>契约:</p>
 * <pre>
 * {
 *   "taskId": 12345,
 *   "documentId": 678,
 *   "contentHash": "a3f8...",
 *   "submittedAt": "2026-08-02T10:15:30Z"
 * }
 * </pre>
 *
 * <p>不可变 record. sentAt 是 producer 写时刻(UTC), parser-service 收到只用于日志, 不作为业务字段。
 *
 * <p>contentHash 冗余传是为了让 parser-service 不依赖查 documents 表就能做幂等校验(同 hash 重复入队 →
 * 通过 uk_parse_tasks_content_hash 唯一索引拒绝)。
 *
 * @param taskId parse_tasks.id(parser-service 据此 lease / 状态迁移)
 * @param documentId documents.id(下载 MinIO raw 文件 + 写 chunks 用)
 * @param contentHash SHA-256 原始文件(幂等 key, 冗余传)
 * @param submittedAt producer 发送时刻(UTC ISO-8601)
 */
public record ParseTaskSubmitMessage(Long taskId, Long documentId, String contentHash, Instant submittedAt) {}
