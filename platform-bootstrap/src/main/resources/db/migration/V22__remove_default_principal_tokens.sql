-- V22: 移除 V9 种入的公开默认 token (P0 安全修复)
--
-- V9 曾把 dev-token-change-me / admin-token-change-me 直接 seed 进 principal 表,
-- 这两个 token 出现在公开仓库(迁移脚本/README/scripts)中, 任何读到仓库的人都可以用它们
-- 通过 AuthFilter 获得 default 租户的 user/admin 身份 — admin 可见全租户文档。
--
-- 处理:
--   1) 本迁移无条件删除这两个已知默认 token 的 principal 行;
--   2) dev/local/test profile 的开发便利由 DefaultPrincipalSeeder (Java, @Profile 门控)
--      启动时幂等补种, 生产 profile 永不种入。
-- 部署方应改用环境变量注入的强随机 token (INSERT principal 或专用脚本)。

DELETE FROM principal
WHERE token IN ('dev-token-change-me', 'admin-token-change-me');
