-- V19: 区分同一文档的执行重试与全量重建，并支持影子 generation 原子切换。

ALTER TABLE documents
    ADD COLUMN active_generation INT NOT NULL DEFAULT 1 AFTER pending_milvus_delete,
    ADD COLUMN pending_generation INT NULL AFTER active_generation;

ALTER TABLE parse_tasks
    DROP INDEX uk_parse_tasks_document,
    ADD COLUMN generation INT NOT NULL DEFAULT 1 AFTER document_id,
    ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'UPLOAD' AFTER generation,
    ADD COLUMN supersedes_task_id BIGINT NULL AFTER trigger_type,
    ADD UNIQUE KEY uk_parse_tasks_doc_generation (document_id, generation),
    ADD KEY idx_parse_supersedes (supersedes_task_id);

ALTER TABLE chunks
    DROP INDEX uk_doc_seq_type,
    ADD COLUMN generation INT NOT NULL DEFAULT 1 AFTER document_id,
    ADD UNIQUE KEY uk_doc_generation_seq_type
        (document_id, generation, seq, chunk_type),
    ADD KEY idx_chunk_doc_generation (document_id, generation, seq);
