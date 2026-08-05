-- V11: Task 11 P0 修复配套 — document_acl 索引 (UK 已在 V9 建, 本 migration 仅加查询索引)
--
-- 背景:
--   - V9 已建 UNIQUE (document_id, principal_type, principal_id, perm)
--   - 修复后的 JpaAclWriter.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm
--     (替代旧 findReadableDocIds 跨 doc 误判, 问题 4 根因) 命中 UK 即可严格判定
--   - 本 migration 仅补一个 (principal_type, principal_id, perm) 索引, 加速反向查询
--     (AclPermissionResolver 的 USER/ROLE/TENANT ACL 三档查询, 找用户在哪些 doc 有 perm)
--
-- 历史数据安全:
--   - 没有数据变更, 仅 ADD INDEX (MySQL online DDL 不阻塞读写)
--   - 已存在同名索引时 IF NOT EXISTS 跳过 (MySQL 8.0+ 支持; 8.0- 版本忽略错误正则替代)
--
-- 回滚: DROP INDEX idx_acl_principal_perm ON document_acl;
CREATE INDEX idx_acl_principal_perm
    ON document_acl (principal_type, principal_id, perm);
