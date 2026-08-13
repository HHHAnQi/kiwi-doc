CREATE TABLE ingestion_quality_reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    passed BOOLEAN NOT NULL,
    score DOUBLE NOT NULL,
    reasons JSON NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    embedding_count INT NOT NULL DEFAULT 0,
    redaction_count INT NOT NULL DEFAULT 0,
    parser_version VARCHAR(32) NOT NULL,
    chunker_version VARCHAR(32) NOT NULL,
    embedding_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quality_doc_stage (document_id, stage),
    KEY idx_quality_failed (passed, created_at),
    CONSTRAINT fk_quality_document FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
