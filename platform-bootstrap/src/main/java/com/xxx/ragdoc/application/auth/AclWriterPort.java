package com.xxx.ragdoc.application.auth;

/**
 * V9 RAG-Perm-001: 把上传文档的 owner ACL 落库的端口。
 *
 * <p>application 层 UploadService 依赖此接口 — 它不感知 infra 的 JPA Repository / Entity, 维持 DDD 分层。 实现:
 * {@code infrastructure.auth.JpaAclWriter}。
 *
 * <p>语义:
 *
 * <ul>
 *   <li>把 {@code documents.visibility / owner_id} 写入 (新建/更新均调)
 *   <li>插入一条 {@code document_acl(USER, owner, OWNER)} 准入记录, 让 OWNER 文档对 PRIVATE 仍可见
 *   <li>幂等: 同 (docId, principal_type=USER, principal_id=owner, perm=OWNER) 已存在时不重复插
 * </ul>
 */
public interface AclWriterPort {

    /**
     * 上传完成时落 owner 视图 (visibility + owner_id) 与 OWNER ACL 准入记录。
     *
     * @param documentId 刚持久化的 document id
     * @param ownerId 上传者 user_id (来自 AuthContext; defaul 主体走 "dev")
     * @param visibility 文档可见性: PRIVATE / TENANT / PUBLIC; null 视作 "TENANT" (单租户兼容)
     */
    void grantOwnerAcl(Long documentId, String ownerId, String visibility);
}
