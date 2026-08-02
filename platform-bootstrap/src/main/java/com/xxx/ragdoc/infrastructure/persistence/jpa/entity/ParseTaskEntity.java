package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * parse_tasks 表 JPA Entity(V3 parser-service 拆分, spec §3.1 / Flyway V5)。
 *
 * <p>与 domain.ParseTask 持久化解耦: 本类是 ORM 模型, 不动业务规则。 domain ↔ entity 翻译在 ParseTaskMapper。attempts
 * 字段(JSON 列) 在 RDB 端按 JSON 字符串存取, mapper 层做 List 序列化。
 */
@Entity
@Table(name = "parse_tasks")
public class ParseTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 状态机 ENUM: PENDING/RUNNING/PARSED/FAILED/CANCELLED. */
    // columnDefinition 显式声明 MySQL ENUM 类型, 让 Hibernate ddl-auto=validate
    // 与 V5 migration DDL 的 ENUM('PENDING','RUNNING','PARSED','FAILED','CANCELLED') 一致校验通过
    // 不写的话 JPA 默认按 String → VARCHAR(16), 与 DDL 的 ENUM 类型不符, validate fail.
    @Column(
            name = "status",
            nullable = false,
            columnDefinition = "ENUM('PENDING','RUNNING','PARSED','FAILED','CANCELLED')")
    private String status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "chunks_written", nullable = false)
    private Integer chunksWritten = 0;

    @Column(name = "chunk_seq_offset", nullable = false)
    private Integer chunkSeqOffset = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_class", length = 200)
    private String errorClass;

    @Column(name = "attempts", columnDefinition = "JSON")
    private String attempts;

    @Column(name = "visible_at", nullable = false)
    private Instant visibleAt;

    @Column(name = "leased_by", length = 50)
    private String leasedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getChunksWritten() {
        return chunksWritten;
    }

    public void setChunksWritten(Integer chunksWritten) {
        this.chunksWritten = chunksWritten;
    }

    public Integer getChunkSeqOffset() {
        return chunkSeqOffset;
    }

    public void setChunkSeqOffset(Integer chunkSeqOffset) {
        this.chunkSeqOffset = chunkSeqOffset;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    public String getAttempts() {
        return attempts;
    }

    public void setAttempts(String attempts) {
        this.attempts = attempts;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }

    public void setVisibleAt(Instant visibleAt) {
        this.visibleAt = visibleAt;
    }

    public String getLeasedBy() {
        return leasedBy;
    }

    public void setLeasedBy(String leasedBy) {
        this.leasedBy = leasedBy;
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
