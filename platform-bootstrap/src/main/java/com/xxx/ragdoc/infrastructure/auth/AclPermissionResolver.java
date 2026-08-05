package com.xxx.ragdoc.infrastructure.auth;

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
 * V9 RAG-Perm-001: 基于 documents + document_acl 表的 {@link PermissionResolverPort} 实现。
 *
 * <p>放在 infrastructure 包是合规的(infra 层可引用 JPA Repository), 让 application 层 RetrieveService
 * 只看 {@code PermissionResolverPort} 接口, 维持 ArchUnit "application 不依赖 infrastructure" 纪律。
 *
 * <p>规则 (短路 + 求并集):
 *
 * <ol>
 *   <li>{@code role:admin} → 返回 null (哨兵: RetrieveService 据此不加 docId 子句, 仅受 tenant 过滤)
 *   <li>默认 principal (tenant=default, rawToken 空): 返 null 让单租户兼容路径走 tenant_id=default,
 *       不强制走 ACL 准入 — 这是 Task 3 要求的"保持单租户兼容"直接体现。
 *   <li>其它 principal:
 *       <ul>
 *         <li>同租户非 PRIVATE (TENANT + PUBLIC) → 并入
 *         <li>跨租户 PUBLIC → 并入
 *         <li>ACL 显式授予: USER / 每条 ROLE / TENANT 三档各查询取并集
 *       </ul>
 * </ol>
 *
 * <p>关于"owner 可读 PRIVATE 文档": 上传时 UploadService 会写一条
 * {@code principal_type=USER, principal_id=owner, perm=OWNER} 的 ACL, OWNER 档属 {@link #READ_PERMS},
 * 自然并入; 这里无需特殊处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AclPermissionResolver implements PermissionResolverPort {

    private static final List<String> READ_PERMS = List.of("READ", "WRITE", "OWNER");

    private final DocumentAclJpaRepository aclRepository;
    private final DocumentJpaRepository documentJpaRepository;

    @Override
    public Set<Long> resolveReadableDocIds(Principal p) {
        if (p == null) {
            return null;
        }
        // 单租户兼容: 默认主体 (匿名/无 token) 直接放行 — 只让 RetrieveService 加 tenant_id=default
        if (isDefaultPrincipal(p)) {
            return null;
        }
        if (p.isAdmin()) {
            return null; // admin 短路
        }

        Set<Long> readable = new HashSet<>();

        // 1) 同租户非 PRIVATE (TENANT + PUBLIC)
        readable.addAll(documentJpaRepository.findNonPrivateDocIdsByTenant(p.tenantId()));

        // 2) 跨租户 PUBLIC
        readable.addAll(documentJpaRepository.findPublicDocIds());

        // 3) ACL 显式授予: USER 档 = user_id
        readable.addAll(aclRepository.findReadableDocIds("USER", p.userId(), READ_PERMS));

        // 4) ACL 显式授予: ROLE 档 = 每个 role
        if (p.roles() != null) {
            for (String role : p.roles()) {
                readable.addAll(aclRepository.findReadableDocIds("ROLE", role, READ_PERMS));
            }
        }

        // 5) ACL 显式授予: TENANT 档 = tenant_id (整租户授权)
        readable.addAll(aclRepository.findReadableDocIds("TENANT", p.tenantId(), READ_PERMS));

        log.debug(
                "permission.resolved user={} tenant={} readable_n={}",
                p.userId(),
                p.tenantId(),
                readable.size());
        return readable;
    }

    /** 单租户兼容判定: 默认主体 (AuthContext.DEFAULT_PRINCIPAL)。 */
    private static boolean isDefaultPrincipal(Principal p) {
        return p.rawToken() != null && p.rawToken().isEmpty()
                && "default".equals(p.tenantId())
                && "dev".equals(p.userId());
    }
}
