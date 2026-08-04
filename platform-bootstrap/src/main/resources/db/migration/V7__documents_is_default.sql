-- V7__documents_is_default.sql
-- Phase 3 / P3-1 (修正版 Phase 3 ADR): 跨版本混查 P0 bug 修复
--
-- 问题: 用户不传 version= 时 retrieve 全库混查, 召回 javax (SCA 2022.x / Spring Boot 2) 代码
-- 实际要 jakarta (SCA 2023.x / Spring Boot 3), 用户信任崩。
--
-- 修: 加 is_default 列, 每 source 至少一个 default 版本, RetrieveService 在用户没显式传
-- version 时按 default 过滤。
--
-- Data migration (首次执行):
--   按 source 分组, 每组找最新 (按 created_at desc) 一条 READY doc 标 is_default=true,
--   其余全 false。空库或单 source 单版本时该 source 唯一 doc 自动 default。
--   后续上传时由 DocumentUploadService 决定是否抢 default (同 source 已有 default 不抢)。

ALTER TABLE documents
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER doc_type;

-- Data migration: 每个 source 的最新 READY doc 标 default
-- 子查询找每组最新的 doc_id, 外层 UPDATE
UPDATE documents d
JOIN (
    SELECT id, source,
           ROW_NUMBER() OVER (PARTITION BY source ORDER BY created_at DESC) AS rn
    FROM documents
    WHERE status = 'READY' AND deleted_at IS NULL
) ranked ON d.id = ranked.id
SET d.is_default = (ranked.rn = 1);

-- 注: 不加 unique index, 因为 deleted_at IS NULL + is_default=true 的 partial unique
-- MySQL 8.4 不支持 partial unique (只有 generated column + unique 变种)。
-- 业务侧 DocumentUploadService + set-default endpoint 保证唯一性。
-- 若上线后发现同 source 多 default bug, 加 trigger 强制约束或迁 Postgres。
