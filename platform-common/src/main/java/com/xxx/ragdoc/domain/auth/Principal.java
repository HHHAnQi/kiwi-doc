package com.xxx.ragdoc.domain.auth;

import java.util.Set;

/**
 * 请求级 principal — 由 AuthFilter 从 token 解析后写入 AuthContext。
 *
 * <p>领域对象: 不带任何 infra 依赖 (JPA/Spring), 让 application 业务层 (RetrieveService / PermissionResolver)
 * 只看接口不看 Entity。
 *
 * @param tenantId 租户 id (default = 单租户)
 * @param userId 用户 id (dev / admin / 业务 user)
 * @param roles 角色集合, 如 {role:default, role:user, role:admin}
 * @param rawToken 原始 Authorization Bearer 值 (空 = 默认 principal fallback)
 */
public record Principal(String tenantId, String userId, Set<String> roles, String rawToken) {

    /** 是否含某 role (放进去时已 normalize 到如 role:admin)。 */
    public boolean hasRole(String role) {
        return role != null && roles != null && roles.contains(role);
    }

    /** 是否为系统管理员 (跨租户可见, 不限制文档)。 */
    public boolean isAdmin() {
        return hasRole("role:admin");
    }
}
