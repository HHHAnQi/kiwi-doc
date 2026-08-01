-- =========================================================
-- V3 文档业务元数据 (source/version/language/doc_type)
-- 详见 docs/data/data-model.md 与 ADR-0001
-- 目的: 支撑元数据过滤检索 / 按组件分组消融 / 跨版本问答
-- =========================================================

ALTER TABLE documents
    ADD COLUMN source   VARCHAR(32) NOT NULL DEFAULT 'unknown' AFTER mime_type,
    ADD COLUMN version  VARCHAR(16) NULL                    AFTER source,
    ADD COLUMN language VARCHAR(8)  NOT NULL DEFAULT 'zh'   AFTER version,
    ADD COLUMN doc_type VARCHAR(16) NOT NULL DEFAULT 'doc'  AFTER language;

-- 按组件查询/分组消融会频繁走这个谓词, 加索引
ALTER TABLE documents ADD INDEX idx_source (source);
