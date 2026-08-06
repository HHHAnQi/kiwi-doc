-- =========================================================
-- PR-6a.2: agent_run — Agent Run 审计与乐观锁
--
-- 设计要点:
--   - run_id 是业务主键 (String, 来自服务端 AgentRunExecutor)
--   - version 是乐观锁 (CAS 条件 UPDATE, 不用 @Version annotation)
--   - budget_json / usage_json / reservation_json 分离 (Reservation≠Usage)
--   - evidence_ids_json 只存 evidenceId 列表, 不存 Evidence 正文 (PR-1 chat_traces 已管)
--   - plan_json 是 PlanValidator 已通过的脱敏 Canonical JSON
--   - 终态由 AgentStateMachine + CAS WHERE status IN (...) 双重保护
-- =========================================================

CREATE TABLE agent_run (
    run_id                  VARCHAR(64)  NOT NULL,
    request_id              VARCHAR(64)  NOT NULL,
    tenant_id               VARCHAR(64)  NOT NULL,
    user_id                 VARCHAR(128) NOT NULL,
    strategy                VARCHAR(32)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,

    plan_id                 VARCHAR(64)  NOT NULL,
    plan_version            VARCHAR(32)  NOT NULL,
    plan_hash               CHAR(64)     NOT NULL,
    plan_json               JSON         NOT NULL,

    budget_json             JSON         NOT NULL,
    reservation_json        JSON         NOT NULL,
    usage_json              JSON         NOT NULL,

    evidence_ids_json       JSON         NULL,
    evidence_count          INT          NOT NULL DEFAULT 0,

    terminal_reason_code    VARCHAR(64)  NULL,

    router_version          VARCHAR(64)  NULL,
    toolset_version         VARCHAR(64)  NULL,
    index_version           VARCHAR(64)  NULL,
    harness_mode            VARCHAR(16)  NOT NULL,

    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version                 BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (run_id),
    UNIQUE KEY uk_agent_run_run_id (run_id),
    KEY idx_agent_run_request_id (request_id),
    KEY idx_agent_run_tenant_created (tenant_id, created_at),
    KEY idx_agent_run_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
