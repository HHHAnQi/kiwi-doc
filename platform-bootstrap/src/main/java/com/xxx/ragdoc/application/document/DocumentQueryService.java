package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.auth.AccessScope;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档查询用例 (读路径: 列表 + 详情)。
 *
 * <p>Task 11 / P0: 所有读操作前置权限校验 -
 *
 * <ul>
 *   <li>{@link #list} 加 tenant + allowedDocumentIds 过滤 (DB 层做, 不在 Java 内存过滤)
 *   <li>{@link #getDetail} 用 {@link DocumentAccessGuard#requireRead} 守门 (含 tenant + ACL)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final DocumentRepository documentRepository;
    private final PermissionResolverPort permissionResolver;
    private final DocumentAccessGuard accessGuard;

    /**
     * 分页查询 (可选 status + 关键字)。
     *
     * <p>Task 11: 强制按当前 principal.tenantId + 显式 allowedDocumentIds 过滤; admin = 本 tenant 全 doc,
     * 普通用户 = allowedDocumentIds 白名单。DB 层 SQL 完成, 防内存过滤越权。
     */
    @Transactional(readOnly = true)
    public Page<DocumentSummary> list(DocumentStatus status, String keyword, Pageable pageable) {
        Principal p = AuthContext.currentPrincipal();
        AccessScope scope = permissionResolver.resolveAccessScope(p);
        return documentRepository.listAccessible(
                p.tenantId(),
                scope.isUnrestrictedWithinTenant() ? null : scope.allowedDocumentIds(),
                status,
                keyword,
                pageable);
    }

    /** 详情 (含 chunk_count) — 不存在 / 跨租户 / 无 ACL → DOC_NOT_FOUND (404)。 */
    @Transactional(readOnly = true)
    public DocumentDetail getDetail(Long id) {
        // Task 11 / P0: 守门 + 不存在/无权 一律 404
        accessGuard.requireRead(id);
        return documentRepository
                .findDetailById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + id));
    }
}
