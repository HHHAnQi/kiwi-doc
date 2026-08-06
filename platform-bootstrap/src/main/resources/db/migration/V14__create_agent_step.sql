-- =========================================================
-- PR-6a.2: agent_step — 单 Step 状态审计与 CAS
--
-- 设计要点:
--   - id 是 DB 代理主键 AUTO_INCREMENT
--   - UNIQUE(run_id, step_id) 是业务唯一键
--   - UNIQUE(run_id, step_sequence) 保证同 Run 内执行序不重复
--   - FK → agent_run(run_id) ON DELETE RESTRICT (审计不级联删)
--   - status VARCHAR 允许全部合法 AgentStepStatus (含 PENDING/RESERVED/RUNNING
--     等运行中状态; Executor 需要在 Run 终止时收敛未完成 Step → CANCELLED)
--   - version 乐观锁 (Step CAS)
--   - evidence_ids_json 只存 evidenceId 列表
-- =========================================================

CREATE TABLE agent_step (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    run_id             VARCHAR(64)  NOT NULL,
    step_id            VARCHAR(64)  NOT NULL,
    step_sequence      INT          NOT NULL,

    tool_name          VARCHAR(64)  NOT NULL,
    tool_version       VARCHAR(32)  NOT NULL,
    call_id            VARCHAR(64)  NULL,
    input_hash         CHAR(64)     NOT NULL,

    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',

    result_count       INT          NOT NULL DEFAULT 0,
    evidence_ids_json  JSON         NULL,

    latency_ms         BIGINT       NULL,
    error_code         VARCHAR(64)  NULL,
    retryable          BOOLEAN      NOT NULL DEFAULT FALSE,
    replayed           BOOLEAN      NOT NULL DEFAULT FALSE,
    deduplicated       BOOLEAN      NOT NULL DEFAULT FALSE,

    started_at         TIMESTAMP    NULL,
    completed_at       TIMESTAMP    NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    version            BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uk_agent_step_run_step UNIQUE (run_id, step_id),
    CONSTRAINT uk_agent_step_run_seq  UNIQUE (run_id, step_sequence),
    CONSTRAINT fk_agent_step_run
        FOREIGN KEY (run_id)
        REFERENCES agent_run(run_id)
        ON DELETE RESTRICT,

    KEY idx_agent_step_run_status (run_id, status),
    KEY idx_agent_step_call_id (call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
