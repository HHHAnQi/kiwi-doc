package com.xxx.ragdoc.application.auth;

import com.xxx.ragdoc.domain.auth.Principal;

/**
 * V9 RAG-Perm-001 / Task 11 P0 安全: 把 Principal 解析成可访问文档范围的应用层端口。
 *
 * <p>RetrieveService(application) 与 DocumentAccessGuard 都依赖此接口, 不直接碰 infra
 * Repository — 维持 ArchUnit "application 不依赖 infrastructure" 纪律。
 *
 * <p>实现: {@code infrastructure.auth.AclPermissionResolver}, 合法引用 JPA Repository。
 *
 * <h2>语义 (Task 11 修正: 不再用 null 哨兵)</h2>
 *
 * <p>返回值 {@link AccessScope} 严格遵守:
 *
 * <ul>
 *   <li>{@code AccessScope.tenantAdmin()==true} (allowedDocumentIds=null): 本租户全可见; <b>仅</b>
 *       对 role:admin 返回此对象, 不允许 anonymous 或普通用户走此路径
 *   <li>{@code AccessScope.allowedDocumentIds()} 非空集合: 显式 ACL+PUBLIC 集合
 *   <li>{@code AccessScope.allowedDocumentIds() == 空集}: 无可读 doc → RetrieveService 短路 NO_RECALL
 * </ul>
 *
 * <p><b>旧实现 bug</b>: 旧版返 {@code Set<Long>}, null 同时表 admin/默认/anonymous, 导致 RetrieveService
 * 把 anonymous 当 admin → 跨 doc 越权 (本次问题 5 根因)。
 */
public interface PermissionResolverPort {

    /**
     * 解析 principal 的可访问文档范围。
     *
     * @param principal 请求级 principal (非空; 由 AuthFilter 写入 AuthContext)
     * @return AccessScope 永不 null (allowedDocumentIds 内 null 仅 tenantAdmin=true 时合法)
     */
    AccessScope resolveAccessScope(Principal principal);

    /**
     * 兼容旧调用: 返回 {@code allowedDocumentIds} 集合 (admin 返 null)。 内部委托 {@link
     * #resolveAccessScope}。 <b>新代码不应调此方法, 用 {@link #resolveAccessScope}</b>。
     *
     * @deprecated Task 11: 用 {@link #resolveAccessScope} 避免歧义
     */
    @Deprecated
    default java.util.Set<Long> resolveReadableDocIds(Principal principal) {
        return resolveAccessScope(principal).allowedDocumentIds();
    }

    /**
     * Task 11 P0: 判断 (documentId, principal, perm) 是否在 ACL 表中存在显式授权。
     *
     * <p>供 DocumentAccessGuard 做 requireRead/Write/Owner 单 doc 严格判断 — 不能复用
     * resolveAccessScope (后者求"用户可见 doc 列表", 量级不同)。
     *
     * <p>检查对象:
     *
     * <ul>
     *   <li>USER 档: (documentId, USER, principal.userId(), perm)
     *   <li>ROLE 档: 任一 (documentId, ROLE, role, perm)
     *   <li>TENANT 档: (documentId, TENANT, principal.tenantId(), perm)
     * </ul>
     *
     * <p>default 返 false 让接口可继续作为 functional interface 用 (旧测试 lambda 调用方便)。
     * 实际 impl 由 AclPermissionResolver 时按需求重写。
     */
    default boolean hasExplicitAcl(Long documentId, Principal principal, String perm) {
        return false;
    }
}
