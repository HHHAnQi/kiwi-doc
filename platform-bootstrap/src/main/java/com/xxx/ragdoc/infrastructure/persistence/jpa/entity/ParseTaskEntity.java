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

    @Column(name = "generation", nullable = false)
    private Integer generation = 1;

    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType = "UPLOAD";

    @Column(name = "supersedes_task_id")
    private Long supersedesTaskId;

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

    @Column(name = "delivery_status", nullable = false, length = 16, insertable = false, updatable = false)
    private String deliveryStatus = "PENDING";

    @Column(name = "delivery_attempts", nullable = false, insertable = false, updatable = false)
    private Integer deliveryAttempts = 0;

    @Column(name = "next_delivery_at", nullable = false, insertable = false, updatable = false)
    private Instant nextDeliveryAt = Instant.now();

    @Column(name = "last_delivered_at", insertable = false, updatable = false)
    private Instant lastDeliveredAt;

    @Column(name = "delivery_error", length = 512, insertable = false, updatable = false)
    private String deliveryError;

    @Column(name = "delivery_leased_by", length = 64, insertable = false, updatable = false)
    private String deliveryLeasedBy;

    @Column(name = "delivery_lease_until", insertable = false, updatable = false)
    private Instant deliveryLeaseUntil;

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

    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Long getSupersedesTaskId() { return supersedesTaskId; }
    public void setSupersedesTaskId(Long supersedesTaskId) { this.supersedesTaskId = supersedesTaskId; }

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

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String value) { this.deliveryStatus = value; }
    public Integer getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(Integer value) { this.deliveryAttempts = value; }
    public Instant getNextDeliveryAt() { return nextDeliveryAt; }
    public void setNextDeliveryAt(Instant value) { this.nextDeliveryAt = value; }
    public Instant getLastDeliveredAt() { return lastDeliveredAt; }
    public void setLastDeliveredAt(Instant value) { this.lastDeliveredAt = value; }
    public String getDeliveryError() { return deliveryError; }
    public void setDeliveryError(String value) { this.deliveryError = value; }
    public String getDeliveryLeasedBy() { return deliveryLeasedBy; }
    public void setDeliveryLeasedBy(String value) { this.deliveryLeasedBy = value; }
    public Instant getDeliveryLeaseUntil() { return deliveryLeaseUntil; }
    public void setDeliveryLeaseUntil(Instant value) { this.deliveryLeaseUntil = value; }

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
