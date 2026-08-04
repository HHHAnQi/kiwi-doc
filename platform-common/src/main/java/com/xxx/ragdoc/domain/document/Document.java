package com.xxx.ragdoc.domain.document;

import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Document 聚合根,V1 简化版。
 *
 * <p>设计契约:
 *
 * <ul>
 *   <li>状态变更必须经业务方法(startParsing/markReady/markFailed/retry/softDelete),不暴露 setter。
 *   <li>所有不变量在方法内自校验,失败抛 IllegalStateException(领域规则违反)。
 *   <li>持久化由 infrastructure 层负责(JPA Entity ↔ Document 双向映射),不污染领域层。
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
    // 业务元数据(上传时一次定型, 不可变): 支撑元数据过滤检索/分组消融/跨版本问答
    // 详见 ADR-0001 与 docs/data/data-model.md。缺省值保证老调用方零改动。
    private final String source; // dubbo/nacos/seata/rocketmq/sentinel/unknown
    private final String version; // 自由版本号, null = 未指定
    private final String language; // zh/en
    private final String docType; // doc/blog/release-notes/spec/demo

    private DocumentStatus status;
    private int retryCount;
    private String errorMessage;
    private List<Chunk> chunks;
    private boolean deleted;
    /**
     * Phase 3 / P3-1 (修正版 Phase 3): 是否为同 source 的默认版本。
     *
     * <p>用途: 用户不传 explicit version 时 RetrieveService 按 is_default fallback 过滤,
     * 避免跨版本混查 (Spring Boot 2 javax vs Spring Boot 3 jakarta)。
     *
     * <p>不变量: 同 source + status=READY + deleted=false 时, 最多 1 条 isDefault=true
     * (DocumentUploadService.upload + AdminEndpoint.setDefault 共同保证)。
     */
    private boolean isDefault;

    // ============================================================
    // 工厂方法
    // ============================================================

    /** 新建 Document(刚上传,尚未持久化,id 为 null,持久化后回填)。无元数据重载, 老调用方零改动。 */
    public static Document newUploaded(
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId) {
        return newUploaded(
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                "unknown",
                null,
                "zh",
                "doc");
    }

    /** 新建 Document 并携带业务元数据(source/version/language/docType)。 */
    public static Document newUploaded(
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId,
            String source,
            String version,
            String language,
            String docType) {
        return new Document(
                null,
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                "unknown".equals(safe(source)) ? "unknown" : safe(source),
                nullIfBlank(version), // version 可空; 空白也视作 null, 防下游 Milvus 写空串污染过滤
                defaultIfBlank(language, "zh"),
                defaultIfBlank(docType, "doc"),
                DocumentStatus.UPLOADED,
                0,
                null,
                new ArrayList<>(),
                false);
    }

    /** 从持久化恢复(由 infrastructure 层重建聚合根)。老重载保留, 委托新方法并补元数据缺省值。 */
    public static Document restore(
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
            boolean deleted) {
        return restore(
                id,
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                status,
                retryCount,
                errorMessage,
                chunks,
                deleted,
                "unknown",
                null,
                "zh",
                "doc");
    }

    /** 从持久化恢复(含业务元数据)。 */
    public static Document restore(
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
            boolean deleted,
            String source,
            String version,
            String language,
            String docType) {
        return new Document(
                id,
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                "unknown".equals(safe(source)) ? "unknown" : safe(source),
                nullIfBlank(version), // 与 newUploaded 一致: 空白视作 null
                defaultIfBlank(language, "zh"),
                defaultIfBlank(docType, "doc"),
                status,
                retryCount,
                errorMessage,
                chunks == null ? new ArrayList<>() : new ArrayList<>(chunks),
                deleted);
    }

    private Document(
            DocumentId id,
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId,
            String source,
            String version,
            String language,
            String docType,
            DocumentStatus status,
            int retryCount,
            String errorMessage,
            List<Chunk> chunks,
            boolean deleted) {
        this.contentHash = Objects.requireNonNull(contentHash);
        this.originalFilename = Objects.requireNonNull(originalFilename);
        this.mimeType = Objects.requireNonNull(mimeType);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负");
        }
        this.sizeBytes = sizeBytes;
        this.tenantId = Objects.requireNonNull(tenantId);

        this.source = Objects.requireNonNull(source, "source 不能为 null, 用 'unknown'");
        this.version = version;
        this.language = Objects.requireNonNull(language, "language 不能为 null, 用 'zh'");
        this.docType = Objects.requireNonNull(docType, "docType 不能为 null, 用 'doc'");

        this.id = id;
        this.status = Objects.requireNonNull(status);
        this.retryCount = retryCount;
        this.errorMessage = errorMessage;
        this.chunks = Objects.requireNonNull(chunks);
        this.deleted = deleted;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String defaultIfBlank(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    /** 空白字符串归一化为 null(version 允许 null 但不允许空串, 防 Milvus 写入空串污染过滤)。 */
    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ============================================================
    // 业务行为(状态机迁移 + 不变量约束)
    // ============================================================

    /**
     * 进入解析。仅 UPLOADED 可迁(V1 同步由 upload 流程立即触发)。
     *
     * <p>幂等表层: 如果已 PARSING, 直接 no-op 返回(RocketMQ redelivery / parser restart 续点会再调一次,
     * 不应抛 IllegalState)。READY/FAILED/UPLOADED 之外的非法迁移仍走 transitionTo 抛。
     */
    public void startParsing() {
        ensureNotDeleted();
        if (this.status == DocumentStatus.PARSING) {
            // MQ redelivery / 重启续点: doc 已在解析, 不重复迁移
            return;
        }
        this.status = status.transitionTo(DocumentStatus.PARSING);
    }

    /** 解析成功。要求 chunks 非空(否则"标记成功却无内容"违反不变量)。 */
    public void markReady(List<Chunk> parsedChunks) {
        ensureNotDeleted();
        if (parsedChunks == null || parsedChunks.isEmpty()) {
            throw new IllegalStateException("markReady 必须 chunks 非空");
        }
        this.status = status.transitionTo(DocumentStatus.READY);
        this.chunks = new ArrayList<>(parsedChunks);
        this.errorMessage = null;
    }

    /** 解析失败。强制附加 errorMessage。 */
    public void markFailed(String errorMessage) {
        ensureNotDeleted();
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalStateException("markFailed 必须带 errorMessage");
        }
        this.status = status.transitionTo(DocumentStatus.FAILED);
        this.errorMessage = errorMessage;
    }

    /** 重试(V1 仅允许 FAILED 且 retry_count=0 时触发一次)。 */
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

    /** 软删。仅 READY/FAILED 可删;PARSING 中不可删。 */
    public void softDelete() {
        if (this.status == DocumentStatus.PARSING) {
            throw new IllegalStateException("PARSING 中不可删除");
        }
        this.deleted = true;
    }

    /**
     * 复活(对称 softDelete)。
     *
     * <p>用于"同 hash 软删 doc 被重新上传"场景: 应用层选择复活老聚合根(保留原 doc_id)而不是 插新 doc, 这样 (a) 不撞
     * documents.uk_content_hash 唯一约束, (b) chunks/Milvus 可走重切路径。 调用方须保证 chunks 已清(reactivate 不负责清旧
     * chunks, 状态机只关心自身)。
     */
    public void reactivate() {
        if (!this.deleted) {
            throw new IllegalStateException("Document 未删除, 无需 reactivate");
        }
        this.deleted = false;
        this.status = DocumentStatus.UPLOADED;
        this.retryCount = 0;
        this.errorMessage = null;
    }

    /** 持久化后回填主键。 */
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

    /** 文档是否被软删(供 application 层 reactivate 分支判断)。 */
    public boolean deleted() {
        return deleted;
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

    /** 业务元数据: 来源组件(dubbo/nacos/seata/rocketmq/sentinel), 缺省 'unknown'。 */
    public String source() {
        return source;
    }

    /** 业务元数据: 版本号(可空)。 */
    public String version() {
        return version;
    }

    /** 业务元数据: 语言(zh/en), 缺省 'zh'。 */
    public String language() {
        return language;
    }

    /** 业务元数据: 文档类型(doc/blog/release-notes/spec/demo), 缺省 'doc'。 */
    public String docType() {
        return docType;
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

    /** chunks 不可变视图。V1 简化:实际切片只在 markReady 时替换。 */
    public List<Chunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean canRetry() {
        return status == DocumentStatus.FAILED && retryCount < 1;
    }

    /** Phase 3 / P3-1: 是否为同 source 的默认版本。 */
    public boolean isDefault() {
        return isDefault;
    }

    /**
     * Phase 3 / P3-1: 标记为同 source 默认版本 (admin set-default 调用)。
     *
     * <p>不变量: 调用方 (DocumentManageService.setDefault) 必须保证先把同 source 老的 default
     * 标 isDefault=false 才调本方法, 维持 "同 source + READY + !deleted 最多 1 default"。
     */
    public void markDefault() {
        this.isDefault = true;
    }

    /** Phase 3 / P3-1: 取消默认标记 (set-default 把老的 default 取消时调)。 */
    public void unmarkDefault() {
        this.isDefault = false;
    }

    @Override
    public String toString() {
        return "Document{id="
                + id
                + ", contentHash="
                + (contentHash == null ? null : contentHash.value().substring(0, 8) + "...")
                + ", status="
                + status
                + ", retryCount="
                + retryCount
                + ", deleted="
                + deleted
                + '}';
    }
}
