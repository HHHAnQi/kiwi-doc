package com.xxx.ragdoc.infrastructure.auth;

import com.xxx.ragdoc.application.auth.AccessScope;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentAclJpaRepository;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentJpaRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V9 RAG-Perm-001 / Task 11 P0: 基于 documents + document_acl 的 {@link PermissionResolverPort} 实现。
 *
 * <p>放 infrastructure 层 (合法引用 JPA Repository); application 层 RetrieveService 只看 port 接口, 维持
 * ArchUnit 纪律。
 *
 * <h2>Task 11 修正 (问题 1+5 根因)</h2>
 *
 * <ul>
 *   <li>不再返 Set&lt;Long&gt; + null 哨兵 (null 同时表 admin / 默认主体 / anonymous)
 *   <li>不再把 anonymous 当 admin 放行 — anonymous 应返空集 NO_RECALL
 *   <li>不再调用 {@code findPublicDocIds()} 跨租户放行 (PUBLIC 仅本 tenant 内公开)
 *   <li>返 {@link AccessScope} 明确 tenantAdmin 与 allowedDocumentIds 含义
 * </ul>
 *
 * <p>规则:
 *
 * <ol>
 *   <li>role:admin → {@link AccessScope#tenantAdmin(String)} (本 tenant 全可见, 跨 tenant 仍拒)
 *   <li>普通用户 → 显式集合并集:
 *       <ul>
 *         <li>本租户 visibility ∈ {TENANT, PUBLIC} 文档 (findNonPrivateDocIdsByTenant)
 *         <li>USER 档 ACL
 *         <li>每 role 的 ROLE 档 ACL
 *         <li>tenant_id 档 ACL (整租户授权)
 *       </ul>
 * </ol>
 *
 * <p>owner 可读 PRIVATE doc: JpaAclWriter 在上传时为 ownerId 写 USER+OWNER ACL, OWNER 档属 {@link
 * #READ_PERMS}, 自然并入显式集; resolver 无需特殊处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AclPermissionResolver implements PermissionResolverPort {

    private static final List<String> READ_PERMS = List.of("READ", "WRITE", "OWNER");

    private final DocumentAclJpaRepository aclRepository;
    private final DocumentJpaRepository documentJpaRepository;

    @Override
    public AccessScope resolveAccessScope(Principal p) {
        if (p == null) {
            // 不应该发生 — AuthFilter fail-closed 后无 principal 即不进 controller
            // 防御性返空集 NO_RECALL, 不返 admin 哨兵
            return AccessScope.of("unknown", Set.of());
        }
        if (p.isAdmin()) {
            // 本租户 admin: 本 tenant 全可见 (跨 tenant 仍由 RetrieveService tenant filter 拦截)
            return AccessScope.tenantAdmin(p.tenantId());
        }

        Set<Long> readable = new HashSet<>();

        // 1) 本租户 visibility ∈ {TENANT, PUBLIC} (Task 11: PUBLIC 仅本 tenant 内; 不跨租户)
        readable.addAll(documentJpaRepository.findNonPrivateDocIdsByTenant(p.tenantId()));

        // 2) USER 档 ACL 显式授予
        readable.addAll(aclRepository.findReadableDocIds("USER", p.userId(), READ_PERMS));

        // 3) ROLE 档 ACL (每 role 一查)
        if (p.roles() != null) {
            for (String role : p.roles()) {
                readable.addAll(aclRepository.findReadableDocIds("ROLE", role, READ_PERMS));
            }
        }

        // 4) TENANT 档 ACL (整租户授权给所有用户)
        readable.addAll(aclRepository.findReadableDocIds("TENANT", p.tenantId(), READ_PERMS));

        log.debug(
                "permission.resolved user={} tenant={} readable_n={}",
                p.userId(),
                p.tenantId(),
                readable.size());
        return AccessScope.of(p.tenantId(), readable);
    }

    @Override
    public boolean hasExplicitAcl(Long documentId, Principal p, String perm) {
        if (documentId == null || p == null || perm == null) return false;
        if (aclRepository.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                documentId, "USER", p.userId(), perm)) {
            return true;
        }
        if (p.roles() != null) {
            for (String role : p.roles()) {
                if (aclRepository.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        documentId, "ROLE", role, perm)) {
                    return true;
                }
            }
        }
        return aclRepository.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                documentId, "TENANT", p.tenantId(), perm);
    }
}
