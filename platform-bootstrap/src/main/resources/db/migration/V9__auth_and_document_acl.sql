-- V9: 文档权限控制系统 (RAG-Perm-001)
-- 1) principal 表: token → (tenant, user, roles)。1 表轻量, 启动 seed dev/admin token。
-- 2) documents 加 visibility + owner_id (NULL=系统/历史遗留, 不阻塞现有数据)。
-- 3) documentacl: 多对多权限授予 (USER/ROLE/TENANT × READ/WRITE/OWNER)。
-- 兼容现有单租户: documents.visibility 默认 'TENANT' → 同租户可见, 历史文档不消失。
-- 复用 documents.tenant_id (V1 已建, 默认 'default'); principal/document_acl 走新表。

-- 1) principal 表
CREATE TABLE principal (
  token        VARCHAR(128) NOT NULL COMMENT 'Authorization Bearer token (与 AuthProperties dev/admin 对齐)',
  tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
  user_id      VARCHAR(64)  NOT NULL,
  roles        VARCHAR(256) NOT NULL DEFAULT 'role:default' COMMENT '逗号分隔: role:default,role:user,role:admin',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token),
  KEY idx_principal_tenant (tenant_id),
  KEY idx_principal_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='token 到 user/tenant/roles 的解析表 (V4 RBAC 起步)';

-- 2) documents 加 visibility + owner_id
ALTER TABLE documents
  ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT'
    COMMENT 'PRIVATE (仅 owner+admin) / TENANT (同租户) / PUBLIC (全租户)',
  ADD COLUMN owner_id   VARCHAR(64) NULL
    COMMENT '上传者 user_id; null=系统/历史遗留 (作 TENANT 可见处理)',
  ADD INDEX idx_documents_owner (owner_id),
  ADD INDEX idx_documents_visibility (visibility);

-- 3) document_acl
CREATE TABLE document_acl (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  document_id    BIGINT       NOT NULL,
  principal_type VARCHAR(16)  NOT NULL COMMENT 'USER / ROLE / TENANT',
  principal_id   VARCHAR(64)  NOT NULL COMMENT 'principal_type 对应的 id (user_id / role:xxx / tenant_id)',
  perm           VARCHAR(16)  NOT NULL COMMENT 'READ / WRITE / OWNER',
  granted_by     VARCHAR(64)  NULL COMMENT '授权者 user_id',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_acl_doc_principal_perm (document_id, principal_type, principal_id, perm),
  KEY idx_acl_doc (document_id),
  KEY idx_acl_principal (principal_type, principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档 ACL: USER/ROLE/TENANT × READ/WRITE/OWNER 授权';

-- 4) seed 默认 principals — 与 AuthProperties.dev-token/admin-token 默认值一致
--    缺 Authorization 头时 AuthFilter 兜底 default 主体, 不靠 DB; 这里只是把显式 token 落表。
INSERT INTO principal (token, tenant_id, user_id, roles) VALUES
  ('dev-token-change-me',   'default', 'dev',   'role:default,role:user'),
  ('admin-token-change-me', 'default', 'admin', 'role:default,role:user,role:admin')
  ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id), user_id = VALUES(user_id), roles = VALUES(roles);
