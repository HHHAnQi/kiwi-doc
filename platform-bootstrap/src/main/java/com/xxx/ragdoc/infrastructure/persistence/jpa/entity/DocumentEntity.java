package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * documents 表 JPA Entity。 与 domain.Document 解耦:本类是持久化模型,不动业务规则。 domain ↔ entity 的换由 {@code
 * DocumentMapper}(infra 层)完成。
 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, length = 64)
    private String mimeType;

    // ===== 业务元数据 (V3 迁移新增) =====
    /** 来源组件: dubbo/nacos/seata/rocketmq/sentinel, 缺省 unknown。 */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "unknown";

    /** 版本号, 可空(未识别版本时为 null)。 */
    @Column(name = "version", length = 16)
    private String version;

    /** 语言: zh / en, 缺省 zh。 */
    @Column(name = "language", nullable = false, length = 8)
    private String language = "zh";

    /** 文档类型: doc / blog / release-notes / spec / demo, 缺省 doc。 */
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType = "doc";

    /** Phase 3 / P3-1: 是否为同 source 的默认版本。 RetrieveService 没 user explicit version 时按此过滤。 */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /**
     * Phase 3 / P3-2: Milvus 向量是否待清理。 softDelete 同步删 chunks (原子),
     * Milvus 走 circuit breaker 失败时标 true, MilvusDeleteSweeper 重试。
     */
    @Column(name = "pending_milvus_delete", nullable = false)
    private Boolean pendingMilvusDelete = false;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    /**
     * V9 RAG-Perm-001: 文档可见性。
     *
     * <ul>
     *   <li>PRIVATE: 仅 owner + role:admin 可读 (ACL 也可显式授权)
     *   <li>TENANT: 同租户所有用户可读 (兼容现有单租户行为)
     *   <li>PUBLIC: 所有租户可读
     * </ul>
     */
    @Column(name = "visibility", nullable = false, length = 16)
    private String visibility = "TENANT";

    /** V9 RAG-Perm-001: 上传者 user_id; null = 系统/历史遗留 (作 TENANT 可见处理)。 */
    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ===== getter / setter(仅 infra 层用,不外泄) =====

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean getPendingMilvusDelete() {
        return pendingMilvusDelete;
    }

    public void setPendingMilvusDelete(Boolean pendingMilvusDelete) {
        this.pendingMilvusDelete = pendingMilvusDelete;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
