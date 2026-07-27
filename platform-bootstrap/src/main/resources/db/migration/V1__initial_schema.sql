-- =========================================================
-- V1 初始 schema
-- 含 documents / chunks / file_objects / feedbacks 四表
-- 详见 docs/data/data-model.md
-- =========================================================

CREATE TABLE documents (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
    content_hash      VARCHAR(64)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(64)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    tenant_id         VARCHAR(32)  NOT NULL DEFAULT 'default',
    retry_count       INT          NOT NULL DEFAULT 0,
    error_message     VARCHAR(512),
    deleted_at        TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_hash (content_hash, tenant_id),
    KEY idx_status (status),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chunks (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    document_id     BIGINT       NOT NULL,
    seq             INT          NOT NULL,
    chunk_type      VARCHAR(16)  NOT NULL,
    content         MEDIUMTEXT   NOT NULL,
    page            INT          NOT NULL,
    bbox            JSON,
    parent_chunk_id BIGINT,
    content_hash    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chunk_doc FOREIGN KEY (document_id) REFERENCES documents(id),
    KEY idx_doc (document_id, seq),
    KEY idx_parent (parent_chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE file_objects (
    document_id BIGINT       PRIMARY KEY,
    object_key  VARCHAR(255) NOT NULL,
    bucket      VARCHAR(64)  NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    checksum    VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_file_doc FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feedbacks (
    id                BIGINT      PRIMARY KEY AUTO_INCREMENT,
    trace_id          VARCHAR(64) NOT NULL,
    rating            VARCHAR(8)  NOT NULL,
    corrected_answer  TEXT,
    comment           TEXT,
    user_id           VARCHAR(32) NOT NULL DEFAULT 'default',
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace (trace_id),
    KEY idx_rating (rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
