package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;

import java.time.Instant;

/**
 * domain.Document ↔ DocumentEntity 双向转换器。
 * infra 层私密知识,不让 domain 感知 JPA 存在。
 */
public final class DocumentMapper {

    private DocumentMapper() {
    }

    /**
     * 从 Entity 重建聚合根。
     */
    public static Document toDomain(DocumentEntity e) {
        return Document.restore(
                new DocumentId(e.getId()),
                new ContentHash(e.getContentHash()),
                e.getOriginalFilename(),
                e.getMimeType(),
                e.getSizeBytes(),
                e.getTenantId(),
                DocumentStatus.valueOf(e.getStatus()),
                e.getRetryCount(),
                e.getErrorMessage(),
                null, // chunks 由独立查询组装,V1 简化
                e.getDeletedAt() != null
        );
    }

    /**
     * 把聚合根状态回写到 Entity(用于 update)。
     * id 来自聚合根(已 assign); 元信息(hash/filename 等)V1 只读,不回写。
     */
    public static DocumentEntity toEntity(Document d, DocumentEntity existing) {
        existing.setStatus(d.status().name());
        existing.setRetryCount(d.retryCount());
        existing.setErrorMessage(d.errorMessage());
        existing.setDeletedAt(d.isDeleted() ? existing.getDeletedAt() != null ? existing.getDeletedAt() : Instant.now() : null);
        existing.setUpdatedAt(Instant.now());
        return existing;
    }

    /**
     * 把聚合根转换为新建 Entity(持久化前)。
     */
    public static DocumentEntity toNewEntity(Document d) {
        DocumentEntity e = new DocumentEntity();
        e.setContentHash(d.contentHash().value());
        e.setOriginalFilename(d.originalFilename());
        e.setMimeType(d.mimeType());
        e.setSizeBytes(d.sizeBytes());
        e.setTenantId(d.tenantId());
        e.setStatus(d.status().name());
        e.setRetryCount(d.retryCount());
        e.setErrorMessage(d.errorMessage());
        return e;
    }
}
