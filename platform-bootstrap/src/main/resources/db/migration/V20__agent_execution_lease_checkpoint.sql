-- V20: Agent 执行租约与结构化 checkpoint。只读、可证明幂等的 Step 才允许未来续跑。

ALTER TABLE agent_run
    ADD COLUMN owner_id VARCHAR(96) NULL AFTER harness_mode,
    ADD COLUMN lease_until TIMESTAMP NULL AFTER owner_id,
    ADD COLUMN heartbeat_at TIMESTAMP NULL AFTER lease_until,
    ADD COLUMN resume_count INT NOT NULL DEFAULT 0 AFTER heartbeat_at,
    ADD INDEX idx_agent_run_lease (status, lease_until);

ALTER TABLE agent_step
    ADD COLUMN idempotency_key CHAR(64) NULL AFTER input_hash,
    ADD COLUMN recoverable BOOLEAN NOT NULL DEFAULT FALSE AFTER idempotency_key,
    ADD COLUMN output_snapshot JSON NULL AFTER evidence_ids_json,
    ADD UNIQUE KEY uk_agent_step_idempotency (idempotency_key);

UPDATE agent_step
SET idempotency_key = SHA2(CONCAT(run_id, ':', step_id, ':', tool_version, ':', input_hash), 256),
    recoverable = tool_name IN (
        'semantic_search', 'keyword_search', 'metadata_search', 'document_fetch', 'citation_verify'
    );

CREATE TABLE agent_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    checkpoint_version BIGINT NOT NULL,
    completed_step_id VARCHAR(64) NULL,
    usage_json JSON NOT NULL,
    reservation_json JSON NOT NULL,
    evidence_ids_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_checkpoint_version UNIQUE (run_id, checkpoint_version),
    CONSTRAINT fk_agent_checkpoint_run FOREIGN KEY (run_id)
        REFERENCES agent_run(run_id) ON DELETE RESTRICT,
    KEY idx_agent_checkpoint_latest (run_id, checkpoint_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
