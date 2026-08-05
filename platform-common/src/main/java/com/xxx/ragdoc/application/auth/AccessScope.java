package com.xxx.ragdoc.application.auth;

import java.util.Set;

/**
 * Task 11 / P0 安全修复: 用户在某次请求里对文档的可访问范围。
 *
 * <p>替代旧 {@code Set<Long>} + null 哨兵双重含义 — 旧设计下 null 既表 admin 又表 anonymous,
 * RetrieveService 误把 anonymous 当 admin, 造成检索越权。
 *
 * <p>新模型: AccessScope 严格区分
 *
 * <ul>
 *   <li>{@link #allowedDocumentIds()} == null: <b>仅</b> 表 tenant admin (本租户全 doc 可见)
 *   <li>{@link #allowedDocumentIds()} == 空集: 本 tenant 内无任何可读 doc (NO_RECALL)
 *   <li>{@link #allowedDocumentIds()} == 非空: 显式白名单 (USER/ROLE/TENANT ACL + 本租户
 *       visibility ∈ {TENANT, PUBLIC} 的合集)
 * </ul>
 *
 * <p>{@link #tenantId()} 不可空 — 所有访问强制 tenant 维度, 防跨租户。
 *
 * <p>{@link #platformWide()} 永远 false — 平台 admin 当前不支持; 跨租户访问一律拒绝。
 * 后续如需加 role:platform_admin, 应在 PermissionResolver 单独构造 platformWide=true 实例。
 *
 * @param tenantId 当前调用主体 tenantId (必填)
 * @param tenantAdmin 是否租户管理员 (本 tenant 全可见, allowedDocumentIds 哨兵=null)
 * @param allowedDocumentIds null = 本 tenant 任意 doc 可见 (admin 哨兵); 非空 = 显式白名单
 */
public record AccessScope(
        String tenantId, boolean tenantAdmin, Set<Long> allowedDocumentIds) {

    public AccessScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("AccessScope.tenantId 不能空");
        }
        // 不变量: allowedDocumentIds=null ⇒ tenantAdmin=true (避免任意 caller 拿 null 当 admin)
        if (allowedDocumentIds == null && !tenantAdmin) {
            throw new IllegalArgumentException(
                    "AccessScope.allowedDocumentIds=null 仅在 tenantAdmin=true 时合法; "
                            + "非 admin 必须传显式集合 (可空集)");
        }
    }

    /** 本 tenant 内无限制 (admin 路径)。 */
    public static AccessScope tenantAdmin(String tenantId) {
        return new AccessScope(tenantId, true, null);
    }

    /** 普通用户: 显式 allowedDocumentIds (可为空集表 NO_RECALL)。 */
    public static AccessScope of(String tenantId, Set<Long> allowed) {
        return new AccessScope(tenantId, false, allowed == null ? Set.of() : allowed);
    }

    /** 是否本 tenant 全点通行 (admin 短路)。 */
    public boolean isUnrestrictedWithinTenant() {
        return tenantAdmin;
    }
}
