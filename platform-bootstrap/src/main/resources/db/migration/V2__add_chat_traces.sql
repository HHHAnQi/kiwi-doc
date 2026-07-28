-- =========================================================
-- V2 增量: chat_traces 表
-- 详见 docs/data/data-model.md 和 ADR-0003(trace_id 软引用决策)
--
-- 设计要点:
--   1. 仅存 query_hash(SHA256), 不存原 query —— 防 PII 长期沉淀
--   2. feedbacks.trace_id 通过应用层软引用此表(不加 FK), 见 ADR-0003
--   3. state_hint 与 domain/shared/StateHint 枚举严格对齐
-- =========================================================

CREATE TABLE chat_traces (
    trace_id      VARCHAR(64)  PRIMARY KEY,
    query_hash    VARCHAR(64)  NOT NULL,
    query_len     INT          NOT NULL,
    answer_len    INT,
    state_hint    VARCHAR(32),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
