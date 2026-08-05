package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import java.time.Instant;

/** domain.Document ↔ DocumentEntity 双向转换器。 infra 层私密知识,不让 domain 感知 JPA 存在。 */
public final class DocumentMapper {

    private DocumentMapper() {}

    /** 从 Entity 重建聚合根。 */
    public static Document toDomain(DocumentEntity e) {
        Document d = Document.restore(
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
                e.getDeletedAt() != null,
                e.getSource(),
                e.getVersion(),
                e.getLanguage(),
                e.getDocType());
        // Phase 3 / P3-1: isDefault 通过业务方法回填 (Document.restore 签名不动, 向后兼容)
        if (Boolean.TRUE.equals(e.getIsDefault())) {
            d.markDefault();
        }
        // Phase 3 / P3-2: pendingMilvusDelete 同样用业务方法回填
        if (Boolean.TRUE.equals(e.getPendingMilvusDelete())) {
            d.markPendingMilvusDelete();
        }
        // Task 4: lastStateChangeAt 反序列化 (状态时间戳, amend-domain 不重新打戳)
        if (e.getLastStateChangeAt() != null) {
            d.amendLastStateChangeAt(e.getLastStateChangeAt());
        }
        return d;
    }

    /** 把聚合根状态回写到 Entity(用于 update)。 id 来自聚合根(已 assign); 元信息(hash/filename 等)V1 只读,不回写。 */
    public static DocumentEntity toEntity(Document d, DocumentEntity existing) {
        existing.setStatus(d.status().name());
        existing.setRetryCount(d.retryCount());
        existing.setErrorMessage(d.errorMessage());
        existing.setDeletedAt(
                d.isDeleted()
                        ? existing.getDeletedAt() != null ? existing.getDeletedAt() : Instant.now()
                        : null);
        existing.setIsDefault(d.isDefault()); // Phase 3 / P3-1
        existing.setPendingMilvusDelete(d.pendingMilvusDelete()); // Phase 3 / P3-2
        existing.setUpdatedAt(Instant.now());
        // Task 4: 持久化聚合根最新 lastStateChangeAt (status 变更时刷新过)
        if (d.lastStateChangeAt() != null) {
            existing.setLastStateChangeAt(d.lastStateChangeAt());
        }
        return existing;
    }

    /** 把聚合根转换为新建 Entity(持久化前)。 */
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
        // 业务元数据(上传时一次定型)
        e.setSource(d.source());
        e.setVersion(d.version());
        e.setLanguage(d.language());
        e.setDocType(d.docType());
        e.setIsDefault(d.isDefault()); // Phase 3 / P3-1
        e.setPendingMilvusDelete(d.pendingMilvusDelete()); // Phase 3 / P3-2
        if (d.lastStateChangeAt() != null) {
            e.setLastStateChangeAt(d.lastStateChangeAt());
        }
        return e;
    }
}
