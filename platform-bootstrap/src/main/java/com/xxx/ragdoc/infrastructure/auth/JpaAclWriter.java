package com.xxx.ragdoc.infrastructure.auth;

import com.xxx.ragdoc.application.auth.AclWriterPort;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentAclEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentAclJpaRepository;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V9 RAG-Perm-001: {@link AclWriterPort} JPA 实现。
 *
 * <p>放 infrastructure 层(可引用 JPA Repository), 让 UploadService(application) 只看 port。
 *
 * <p>不变量:
 *
 * <ul>
 *   <li>{@code visibility} normalize: null/blank → "TENANT" (单租户兼容)
 *   <li>{@code ownerId} null/blank 时不停在此 (跳过 ACL 写入但仍更新 visibility), 防 AuthContext 缺主体时
 *       把整条上传打断 — 单租户兼容优于 ACL 完整性
 *   <li>OWNER ACL 已存在则跳过 (uk_acl_doc_principal_perm 唯一约束保证)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaAclWriter implements AclWriterPort {

    private static final String PERM_OWNER = "OWNER";
    private static final String PRINCIPAL_USER = "USER";
    private static final String DEFAULT_VISIBILITY = "TENANT";

    private final DocumentAclJpaRepository aclRepository;
    private final DocumentJpaRepository documentJpaRepository;

    @Override
    public void grantOwnerAcl(Long documentId, String ownerId, String visibility) {
        String vis = (visibility == null || visibility.isBlank()) ? DEFAULT_VISIBILITY : visibility.toUpperCase();

        // 1) documents.visibility + owner_id 写回
        documentJpaRepository
                .findById(documentId)
                .ifPresent(
                        doc -> {
                            doc.setVisibility(vis);
                            if (ownerId != null && !ownerId.isBlank()) {
                                doc.setOwnerId(ownerId);
                            }
                            documentJpaRepository.save(doc);
                        });

        // 2) ACL: USER + owner + OWNER (让 PRIVATE 文档 owner 自己也能读)
        if (ownerId == null || ownerId.isBlank()) {
            log.warn(
                    "acl.skip_owner_null doc_id={} (AuthContext 无主体, 仅落 visibility={})",
                    documentId,
                    vis);
            return;
        }
        // Task 11 P0 修复 (问题 4):
        //   旧代码 aclRepository.findReadableDocIds(USER, ownerId, [OWNER]).isEmpty() 无 documentId
        //   维度, owner 在任意 doc 上有 OWNER 即返 true → 第二份文档跳过 ACL 写入。
        //   新代码严格按 (documentId, USER, ownerId, OWNER) 判定, 真实反映"当前 doc 是否已有此 ACL"。
        boolean alreadyGranted =
                aclRepository.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        documentId, PRINCIPAL_USER, ownerId, PERM_OWNER);
        if (alreadyGranted) {
            log.debug("acl.owner_already_granted doc_id={}, owner={}", documentId, ownerId);
            return;
        }
        try {
            DocumentAclEntity acl = new DocumentAclEntity();
            acl.setDocumentId(documentId);
            acl.setPrincipalType(PRINCIPAL_USER);
            acl.setPrincipalId(ownerId);
            acl.setPerm(PERM_OWNER);
            acl.setGrantedBy(ownerId);
            aclRepository.save(acl);
            log.info("acl.owner_granted doc_id={}, owner={}, visibility={}", documentId, ownerId, vis);
        } catch (org.springframework.dao.DataIntegrityViolationException ukEx) {
            // V9 唯一键 (document_id, principal_type, principal_id, perm) 冲突 = 并发已写
            // → 视为已存在, 不挂主流程 (Task 11 问题 4 的并发幂等)
            log.info(
                    "acl.owner_granted_concurrent doc_id={}, owner={} (UK conflict → idempotent)",
                    documentId,
                    ownerId);
        }
    }
}
