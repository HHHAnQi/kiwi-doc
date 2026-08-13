-- V17: 把 parse_tasks 从“PENDING 行反复盲发”升级为有确认状态和退避时间的任务型 outbox。
-- 任务执行状态与消息投递状态仍在同一聚合内，避免为单一事件额外引入一张表。

ALTER TABLE parse_tasks
    DROP INDEX uk_parse_tasks_content_hash,
    ADD UNIQUE KEY uk_parse_tasks_document (document_id),
    ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER leased_by,
    ADD COLUMN delivery_attempts INT NOT NULL DEFAULT 0 AFTER delivery_status,
    ADD COLUMN next_delivery_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER delivery_attempts,
    ADD COLUMN last_delivered_at TIMESTAMP NULL AFTER next_delivery_at,
    ADD COLUMN delivery_error VARCHAR(512) NULL AFTER last_delivered_at,
    ADD INDEX idx_parse_delivery (delivery_status, next_delivery_at);

-- 历史终态/RUNNING 任务不应在升级后被重新投递；只有真正待处理的 PENDING 保持待发。
UPDATE parse_tasks
SET delivery_status = CASE WHEN status = 'PENDING' THEN 'PENDING' ELSE 'SENT' END,
    next_delivery_at = visible_at;
