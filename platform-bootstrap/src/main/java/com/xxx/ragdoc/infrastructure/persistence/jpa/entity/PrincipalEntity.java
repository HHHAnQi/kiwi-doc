package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * principal 表 Entity (V9 RAG-Perm-001)。
 *
 * <p>token (PK) → (tenant_id, user_id, roles)。AuthFilter 解析 Authorization Bearer 时查这张表;
 * 找不到/无 header 走默认 principal, 不直接抛 401 (单租户兼容)。
 *
 * <p>记录是 infra 持久化模型; application 层通过 PermissionResolver 看 domain.Principal。
 */
@Entity
@Table(name = "principal")
public class PrincipalEntity {

    @Id
    @Column(name = "token", nullable = false, length = 128)
    private String token;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** CSV: role:default,role:user,role:admin */
    @Column(name = "roles", nullable = false, length = 256)
    private String roles = "role:default";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ===== getters / setters =====

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
