package com.xxx.ragdoc.application.chat.tool;

import java.util.Set;

/**
 * PR-4 / EMS-PR4: 单次 Tool 调用的权限范围。tenantId 只能从 {@link ToolExecutionContext#principal()} 派生,
 * <b>不接受</b> 客户端/LLM 传入。
 *
 * <h2>关键不变量</h2>
 *
 * <ul>
 *   <li>{@link #tenantId()} 必填, 与 Principal.tenantId() 一致; 不允许 null / 空 / 跨租户值
 *   <li>{@link #allowedDocumentIds()}:
 *       <ul>
 *         <li>{@code null} = tenant admin / 本租户全可见 (向后兼容 AccessScope 语义)
 *         <li>空 Set = NO_RECALL sentinel (deny-by-default; ACL 已确定一条都不可见)
 *         <li>非空 Set = 显式白名单
 *       </ul>
 *   <li>{@link #permissionScopeVersion()} = 当前 ACL 集合的稳定版本号。当前 ACL 表无内置 version (审计结论), PR-4 用 ACL
 *       行数 + 集合的 hash 派生一个稳定的字符串; ACL 变化 (grant/revoke) → 版本号变化 → 让 Tool dedup / cache
 *       不会跨权限版本错误复用结果(EMS-PR4 §10)
 * </ul>
 *
 * <p>不可变 record; 为简化构造, 提供 {@link #of(String, boolean, Set, String)} 工厂。
 */
public record PermissionScope(
        String tenantId,
        boolean tenantAdmin,
        Set<Long> allowedDocumentIds,
        String permissionScopeVersion) {

    public PermissionScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("PermissionScope.tenantId 必填, 来自 Principal");
        }
        if (allowedDocumentIds != null) {
            allowedDocumentIds = Set.copyOf(allowedDocumentIds);
        }
        if (permissionScopeVersion == null || permissionScopeVersion.isBlank()) {
            // 缺省租户标识 + 集合 size, 至少能区分 admin / 非空集 / 空集大小变化
            permissionScopeVersion =
                    tenantId
                            + "|"
                            + (allowedDocumentIds == null
                                    ? "admin"
                                    : ("n=" + allowedDocumentIds.size()));
        }
    }

    /** tenant admin 工厂: 不限制文档集。 */
    public static PermissionScope adminOf(String tenantId, String version) {
        return new PermissionScope(tenantId, true, null, version);
    }

    /** 普通用户工厂: tenantId + 白名单 docIds + version。 */
    public static PermissionScope of(String tenantId, Set<Long> allowed, String version) {
        return new PermissionScope(tenantId, false, allowed, version);
    }
}
