-- =========================================================
-- V5 parser-service 任务表 (ADR-0005 / V3-W1 / spec docs/v3/parser-service-spec.md)
--
-- 目的: 拆 chat-app 同步 TikaParsingTrigger 链路为 MQ 异步。
-- chat-app 上传完写 PENDING→ 发 RocketMQ → parser-service 消费 → 写 chunks + Milvus
-- → UPDATE PARSED。
--
-- 设计要点:
--   - content_hash UNIQUE = 幂等 key(SHA-256 原始文件)
--   - visible_at = AWS SQS-style visibility timeout; 心跳 job 把过期 RUNNING 回 PENDING
--   - leased_by = worker hostname+pid, 跨 worker 防 "工人长跑"
--   - chunks_written + chunk_seq_offset = V3 续点字段(中断后重启从 chunk_seq_offset 续)
--   - max_retries = 手动入 DLQ 前重试上限(默认 3)
--   - attempts JSON = 历史 attempt(时间/错/持续时长), 便于 dead letter 分析
-- =========================================================

CREATE TABLE parse_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 原始文件, 幂等 key',

    -- 状态机: PENDING → RUNNING → PARSED (或 FAILED → 重试 PENDING → ..., CANCELLED)
    status ENUM('PENDING','RUNNING','PARSED','FAILED','CANCELLED')
        NOT NULL DEFAULT 'PENDING',

    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3 COMMENT '达到后入 DLQ 终态, 不再调度',

    -- V3 续点字段: chunk-level(ADR-0009 D2). page-level 推 V3.5
    chunks_written INT NOT NULL DEFAULT 0 COMMENT '已成功落库 chunks 数',
    chunk_seq_offset INT NOT NULL DEFAULT 0 COMMENT '下次 chunk.seq 起始值, 重启续点用',

    -- 故障信息
    error_message TEXT NULL,
    error_class VARCHAR(200) NULL,
    attempts JSON NULL COMMENT '每次 attempt 历史 [{ts, duration_ms, error}]',

    -- visibility timeout + lease(防 zombie worker)
    visible_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        COMMENT '此时间之前不允许其他 worker 抢',
    leased_by VARCHAR(50) NULL COMMENT 'worker hostname+pid',

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_parse_tasks_content_hash UNIQUE (content_hash),
    CONSTRAINT fk_parse_tasks_document FOREIGN KEY (document_id)
        REFERENCES documents(id) ON DELETE CASCADE,

    KEY idx_parse_status_visible (status, visible_at),
    KEY idx_parse_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='V3 parser-service 任务表 - MQ 异步 pipeline + 中断恢复';
