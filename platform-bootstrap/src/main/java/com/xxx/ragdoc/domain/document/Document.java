package com.xxx.ragdoc.domain.document;

import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Document 聚合根,V1 简化版。
 * <p>
 * 设计契约:
 * <ul>
 *   <li>状态变更必须经业务方法(startParsing/markReady/markFailed/retry/softDelete),不暴露 setter。</li>
 *   <li>所有不变量在方法内自校验,失败抛 IllegalStateException(领域规则违反)。</li>
 *   <li>持久化由 infrastructure 层负责(JPA Entity ↔ Document 双向映射),不污染领域层。</li>
 * </ul>
 *
 * 详见 docs/architecture/domain-model.md §2。
 */
public class Document {

    private DocumentId id;
    private final ContentHash contentHash;
    private final String originalFilename;
    private final String mimeType;
    private final long sizeBytes;
    private final String tenantId;

    private DocumentStatus status;
    private int retryCount;
    private String errorMessage;
    private List<Chunk> chunks;
    private boolean deleted;

    // ============================================================
    // 工厂方法
    // ============================================================

    /**
     * 新建 Document(刚上传,尚未持久化,id 为 null,持久化后回填)。
     */
    public static Document newUploaded(
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId
    ) {
        return new Document(
                null, contentHash, originalFilename, mimeType, sizeBytes, tenantId,
                DocumentStatus.UPLOADED, 0, null, new ArrayList<>(), false);
    }

    /**
     * 从持久化恢复(由 infrastructure 层重建聚合根)。包私有,仅允许同包调用。
     */
    static Document restore(
            DocumentId id,
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId,
            DocumentStatus status,
            int retryCount,
            String errorMessage,
            List<Chunk> chunks,
            boolean deleted
    ) {
        return new Document(
                id, contentHash, originalFilename, mimeType, sizeBytes, tenantId,
                status, retryCount, errorMessage,
                chunks == null ? new ArrayList<>() : new ArrayList<>(chunks),
                deleted);
    }

    private Document(
            DocumentId id, ContentHash contentHash, String originalFilename, String mimeType,
            long sizeBytes, String tenantId,
            DocumentStatus status, int retryCount, String errorMessage,
            List<Chunk> chunks, boolean deleted
    ) {
        this.contentHash = Objects.requireNonNull(contentHash);
        this.originalFilename = Objects.requireNonNull(originalFilename);
        this.mimeType = Objects.requireNonNull(mimeType);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负");
        }
        this.sizeBytes = sizeBytes;
        this.tenantId = Objects.requireNonNull(tenantId);

        this.id = id;
        this.status = Objects.requireNonNull(status);
        this.retryCount = retryCount;
        this.errorMessage = errorMessage;
        this.chunks = Objects.requireNonNull(chunks);
        this.deleted = deleted;
    }

    // ============================================================
    // 业务行为(状态机迁移 + 不变量约束)
    // ============================================================

    /**
     * 进入解析。仅 UPLOADED 可迁(V1 同步由 upload 流程立即触发)。
     */
    public void startParsing() {
        ensureNotDeleted();
        this.status = status.transitionTo(DocumentStatus.PARSING);
    }

    /**
     * 解析成功。要求 chunks 非空(否则"标记成功却无内容"违反不变量)。
     */
    public void markReady(List<Chunk> parsedChunks) {
        ensureNotDeleted();
        if (parsedChunks == null || parsedChunks.isEmpty()) {
            throw new IllegalStateException("markReady 必须 chunks 非空");
        }
        this.status = status.transitionTo(DocumentStatus.READY);
        this.chunks = new ArrayList<>(parsedChunks);
        this.errorMessage = null;
    }

    /**
     * 解析失败。强制附加 errorMessage。
     */
    public void markFailed(String errorMessage) {
        ensureNotDeleted();
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalStateException("markFailed 必须带 errorMessage");
        }
        this.status = status.transitionTo(DocumentStatus.FAILED);
        this.errorMessage = errorMessage;
    }

    /**
     * 重试(V1 仅允许 FAILED 且 retry_count=0 时触发一次)。
     */
    public void retry() {
        ensureNotDeleted();
        if (status != DocumentStatus.FAILED) {
            throw new IllegalStateException("仅 FAILED 文档可重试");
        }
        if (retryCount >= 1) {
            throw new IllegalStateException("V1 仅允许重试一次, 当前 retryCount=" + retryCount);
        }
        this.status = status.transitionTo(DocumentStatus.PARSING);
        this.retryCount = retryCount + 1;
        this.errorMessage = null;
    }

    /**
     * 软删。仅 READY/FAILED 可删;PARSING 中不可删。
     */
    public void softDelete() {
        if (this.status == DocumentStatus.PARSING) {
            throw new IllegalStateException("PARSING 中不可删除");
        }
        this.deleted = true;
    }

    /**
     * 持久化后回填主键。
     */
    public void assignId(DocumentId id) {
        if (this.id != null) {
            throw new IllegalStateException("Document 已有 id, 不允许重新 assign");
        }
        this.id = Objects.requireNonNull(id);
    }

    // ============================================================
    // 校验辅助
    // ============================================================
    private void ensureNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Document 已删除, 不允许变更");
        }
    }

    // ============================================================
    // 只读访问
    // ============================================================
    public DocumentId id() {
        return id;
    }

    public ContentHash contentHash() {
        return contentHash;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public String mimeType() {
        return mimeType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String tenantId() {
        return tenantId;
    }

    public DocumentStatus status() {
        return status;
    }

    public int retryCount() {
        return retryCount;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /**
     * chunks 不可变视图。V1 简化:实际切片只在 markReady 时替换。
     */
    public List<Chunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean canRetry() {
        return status == DocumentStatus.FAILED && retryCount < 1;
    }

    @Override
    public String toString() {
        return "Document{id=" + id
                + ", contentHash=" + (contentHash == null ? null : contentHash.value().substring(0, 8) + "...")
                + ", status=" + status + ", retryCount=" + retryCount
                + ", deleted=" + deleted + '}';
    }
}
