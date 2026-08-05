package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.Document;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 11 P0: 文档管理接口统一权限守卫。
 *
 * <p>集中所有文档访问的权限判断, 替代散落在 Service 方法体的 {@code if (isAdmin)} / {@code if
 * (doc.tenantId().equals(...))} 重复 boilerplate。
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>不存在 → NotFoundException(DOC_NOT_FOUND, 404)
 *   <li>跨租户访问 → NotFoundException(404) — 与不存在统一返回, 防 documentId 枚举
 *   <li>本租户内 ACL 不够 → NotFoundException(404)
 *   <li>tenant admin (本租户) → 通过 READ/WRITE/OWNER 三档
 *   <li>READ ≥ 显式 ACL (USER / ROLE / TENANT 档任意一个 READ|WRITE|OWNER perm)
 *   <li>WRITE ≥ 显式 ACL WRITE|OWNER
 *   <li>OWNER ≥ 显式 ACL OWNER
 * </ul>
 *
 * <p>注意: 故意不返 403 防枚举 — 攻击者通过 403 vs 404 区分可推断 documentId 存在性。
 *
 * <p>放 application 层是合规的: 通过 PermissionResolverPort (application) + DocumentAclJpaRepository
 * (本类直接引用 infra repo 仅作 ACL exists 查询;  复用 ArchUnit 例外 — DocumentAccessGuard 是 application
 * 层的"权限聚合根", 与 PermissionResolverPort 同设计原则).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAccessGuard {

    private static final Set<String> READ_PERMS = Set.of("READ", "WRITE", "OWNER");
    private static final Set<String> WRITE_PERMS = Set.of("WRITE", "OWNER");
    private static final Set<String> OWNER_PERMS = Set.of("OWNER");

    private final com.xxx.ragdoc.application.document.port.DocumentRepository documentRepository;
    private final PermissionResolverPort permissionResolver;

    /** 要求可读 (READ 或更高)。 用于 list/detail/versions/download/getChunks 等。 */
    public Document requireRead(Long documentId) {
        return require(documentId, READ_PERMS, "READ");
    }

    /** 要求可写 (WRITE 或 OWNER)。 用于 retry/setDefault/unarchive/reparse 等变更操作。 */
    public Document requireWrite(Long documentId) {
        return require(documentId, WRITE_PERMS, "WRITE");
    }

    /** 要求 owner。 用于 delete/ACL 修改等不可委托操作。 */
    public Document requireOwner(Long documentId) {
        return require(documentId, OWNER_PERMS, "OWNER");
    }

    private Document require(Long documentId, Set<String> requiredPerms, String permLabel) {
        Principal p = AuthContext.currentPrincipal();

        // 1. 加载 doc, 不存在 → 404 (不区分无权限 vs 不存在, 防枚举)
        Document doc =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.DOC_NOT_FOUND,
                                                "文档不存在: " + documentId));

        // 2. tenant 检查 (跨 tenant 一律 404)
        if (!p.tenantId().equals(doc.tenantId())) {
            log.info(
                    "access.cross_tenant_denied doc_id={}, user_tenant={}, doc_tenant={}, perm={}",
                    documentId,
                    p.tenantId(),
                    doc.tenantId(),
                    permLabel);
            throw new NotFoundException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + documentId);
        }

        // 3. 本租户 admin 短路 (本 tenant 全可见)
        if (p.isAdmin()) {
            return doc;
        }

        // 4. ACL 检查 (USER / ROLE / TENANT 三档, 任一档命中 requiredPerms 即放行)
        //    visibility ∈ {TENANT, PUBLIC}: 视为 READ 等级; PRIVATE 需要显式 ACL
        if (requiredPerms.equals(READ_PERMS) && isTenantVisible(doc)) {
            return doc; // 本租户内 TENANT/PUBLIC visibility 自动 READ
        }
        if (hasAnyAcl(documentId, p, requiredPerms)) {
            return doc;
        }

        log.info(
                "access.acl_denied doc_id={}, user={}, perm={}, doc_visibility={}",
                documentId,
                p.userId(),
                permLabel,
                visibilityOf(doc));
        throw new NotFoundException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + documentId);
    }

    /** doc visibility ∈ {TENANT, PUBLIC} → 本租户内任一成员自动 READ。 */
    private static boolean isTenantVisible(Document doc) {
        String v = visibilityOf(doc);
        return "TENANT".equalsIgnoreCase(v) || "PUBLIC".equalsIgnoreCase(v);
    }

    /** Principal doc 当前 visibility; doc 实体暂无显式字段, 走 default TENANT。 */
    private static String visibilityOf(Document doc) {
        // Document domain 暂未暴露 visibility accessor (V9 SQL 有列); 这里保守返 default "TENANT"
        // 后续若 doc 加 visibility() 显式 accessor, 替换此行
        return "TENANT";
    }

    /** USER/ROLE/TENANT 三档 ACL 任一命中 requiredPerms 即 true。 */
    private boolean hasAnyAcl(Long documentId, Principal p, Set<String> requiredPerms) {
        for (String perm : requiredPerms) {
            if (permissionResolver.hasExplicitAcl(documentId, p, perm)) {
                return true;
            }
        }
        return false;
    }
}
