-- V8__documents_pending_milvus_delete.sql
-- Phase 3 / P3-2 (修正版 Phase 3 ADR): 软删同步删 Milvus P0 修复
--
-- 问题: 软删 Document 只设 deletedAt, 没清 Milvus 向量, 已软删文档的向量随后被 retrieve 召回 →
-- 用户拿到"已删除"文档的 chunks (幽灵召回)。
--
-- 修: 软删时同步删 chunks (in-tx, 原子) + 异步删 Milvus (out-of-tx, 走 circuit breaker)。
-- Milvus 调用可能熔断 / 超时, 软删主流程不能挂死 → mark pending=true 让 sweeper 重试。
--
-- 同 source 已 default 软删时必须先 unmark default 让 DocumentUploadService 下次能上新 default;
-- 这部分逻辑在 domain + DocumentManageService, 不在本 DDL。

ALTER TABLE documents
    ADD COLUMN pending_milvus_delete BOOLEAN NOT NULL DEFAULT FALSE AFTER is_default;

-- Sweeper 查 pending=true 的索引; MySQL 8 不支持 partial index (WHERE 子句), 用全覆盖普通索引。
-- 行数稀疏 (大多数 pending=false), 索引选择性够, B+ tree 走得动。
CREATE INDEX idx_documents_pending_milvus_delete
    ON documents (pending_milvus_delete);
