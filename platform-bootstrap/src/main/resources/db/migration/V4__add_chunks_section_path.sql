-- =========================================================
-- V4 chunks.section_path (Q3-B)
-- 详见 ADR-0004 与 docs/data/data-model.md
-- 目的: 给每个 chunk 标注它所属的 markdown heading 路径(h1/h2/h3 栈)
-- 支撑: ChatResponse.Citation.section_path 暴露给前端, 用于 chapter 级溯源
-- 没有 heading 上下文的 chunk(纯代码块/表格/段) 值为 NULL = 向后兼容老 chunks
-- =========================================================

ALTER TABLE chunks
    ADD COLUMN section_path JSON NULL AFTER content_hash;
