package com.xxx.ragdoc.domain.document;

import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.time.Instant;
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
    /** 同一逻辑文件跨版本稳定不变；默认由上传层从文件名推导，也可由连接器提供外部稳定 ID。 */
    private final String logicalDocumentKey;
    private final String language; // zh/en
    private final String docType; // doc/blog/release-notes/spec/demo

    private DocumentStatus status;
    private int retryCount;
    private String errorMessage;
    private List<Chunk> chunks;
    private boolean deleted;

    /**
     * 是否为同一逻辑文档的当前版本（字段名保留 default 兼容旧 API）。
     *
     * <p>用途: 用户不传 explicit version 时 RetrieveService 只召回每个逻辑文档的 current 版本。
     *
     * <p>不变量: 同 tenant + logicalDocumentKey + deleted=false 最多 1 条 isDefault=true，数据库唯一约束兜底。
     */
    private boolean isDefault;

    /**
     * Phase 3 / P3-2 (修正版 Phase 3): Milvus 向量是否待清理。
     *
     * <p>用途: 软删文档时, chunks 在 @Transactional 内原子清, 但 Milvus 向量删除走 circuit breaker 可能熔断/超时, 不能阻塞
     * softDelete 主流程。失败时 markPendingMilvusDelete() 标记 pending=true, MilvusDeleteSweeper 定时扫
     * pending=true 重试, 成功后 clear()。
     *
     * <p>不变量: 新建 doc 永远 pending=false; reactivate 时 clear (避免历史脏标记触发 sweeper 误删)。
     */
    private boolean pendingMilvusDelete;
    private int activeGeneration = 1;
    private Integer pendingGeneration;

    /**
     * Task 4: 状态机最后变更时间。reconcile job 扫「in-flight 超时」用: status IN
     * (PARSING/CHUNKED/EMBEDDING/INDEXING) AND lastStateChangeAt < now - 30min 即视为卡死。
     */
    private Instant lastStateChangeAt;

    /** Task 4: 自动/手动重试上限 (原 V1 为 1, V10 放宽到 3)。 */
    private static final int MAX_RETRY = 3;

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
        return newUploaded(
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                source,
                version,
                language,
                docType,
                null);
    }

    public static Document newUploaded(
            ContentHash contentHash,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String tenantId,
            String source,
            String version,
            String language,
            String docType,
            String logicalDocumentKey) {
        return new Document(
                null,
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                "unknown".equals(safe(source)) ? "unknown" : safe(source),
                nullIfBlank(version), // version 可空; 空白也视作 null, 防下游 Milvus 写空串污染过滤
                defaultIfBlank(logicalDocumentKey, legacyLogicalKey(originalFilename)),
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
                "doc",
                legacyLogicalKey(originalFilename));
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
                source,
                version,
                language,
                docType,
                null);
    }

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
            String docType,
            String logicalDocumentKey) {
        return new Document(
                id,
                contentHash,
                originalFilename,
                mimeType,
                sizeBytes,
                tenantId,
                "unknown".equals(safe(source)) ? "unknown" : safe(source),
                nullIfBlank(version), // 与 newUploaded 一致: 空白视作 null
                defaultIfBlank(logicalDocumentKey, legacyLogicalKey(originalFilename)),
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
            String logicalDocumentKey,
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
        this.logicalDocumentKey =
                Objects.requireNonNull(logicalDocumentKey, "logicalDocumentKey 不能为 null");
        this.language = Objects.requireNonNull(language, "language 不能为 null, 用 'zh'");
        this.docType = Objects.requireNonNull(docType, "docType 不能为 null, 用 'doc'");

        this.id = id;
        this.status = Objects.requireNonNull(status);
        this.retryCount = retryCount;
        this.errorMessage = errorMessage;
        this.chunks = Objects.requireNonNull(chunks);
        this.deleted = deleted;
        this.lastStateChangeAt = Instant.now();
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

    private static String legacyLogicalKey(String filename) {
        return filename == null ? "unknown" : filename.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ============================================================
    // 业务行为(状态机迁移 + 不变量约束)
    // ============================================================

    /**
     * 进入解析。仅 UPLOADED 可迁(V1 同步由 upload 流程立即触发)。
     *
     * <p>幂等表层: 如果已 PARSING, 直接 no-op 返回(RocketMQ redelivery / parser restart 续点会再调一次, 不应抛
     * IllegalState)。READY/FAILED/UPLOADED 之外的非法迁移仍走 transitionTo 抛。
     *
     * <p>Task 4: 同样幂等适用于 CHUNKED/EMBEDDING/INDEXING (parse 队列重投, 已在管道中, 不重复迁移)。
     */
    public void startParsing() {
        ensureNotDeleted();
        if (this.status == DocumentStatus.PARSING
                || this.status == DocumentStatus.CHUNKED
                || this.status == DocumentStatus.EMBEDDING
                || this.status == DocumentStatus.INDEXING) {
            // MQ redelivery / 重启续点: doc 已在管道中, 不重复迁移
            return;
        }
        this.status = status.transitionTo(DocumentStatus.PARSING);
        touchStateChange();
    }

    /**
     * Task 4: 切片完成, 持久化 chunks 并迁到 CHUNKED。
     *
     * <p>替代旧 {@code markReady(List<Chunk>)} — 索引生命周期里"切片完成"只是中间态, 不再是终态。
     *
     * @throws IllegalStateException 若 chunks 空 (违反不变量)
     */
    public void markChunked(List<Chunk> parsedChunks) {
        ensureNotDeleted();
        if (parsedChunks == null || parsedChunks.isEmpty()) {
            throw new IllegalStateException("markChunked 必须 chunks 非空");
        }
        this.status = status.transitionTo(DocumentStatus.CHUNKED);
        this.chunks = new ArrayList<>(parsedChunks);
        this.errorMessage = null;
        touchStateChange();
    }

    /**
     * Task 4: 进入 embedding 阶段 (调 embedding API 前)。
     *
     * <p>无业务数据变更, 仅状态机推进 — 让 reconcile job 能识别"卡在 embedding"。
     */
    public void markEmbedding() {
        ensureNotDeleted();
        this.status = status.transitionTo(DocumentStatus.EMBEDDING);
        touchStateChange();
    }

    /** Task 4: 进入 indexing 阶段 (Milvus upsert 前)。 */
    public void markIndexing() {
        ensureNotDeleted();
        this.status = status.transitionTo(DocumentStatus.INDEXING);
        touchStateChange();
    }

    /**
     * Task 4: 索引完成, 终态。
     *
     * <p>替代旧 {@code markReady} — 此状态后才允许检索。
     */
    public void markIndexed() {
        ensureNotDeleted();
        this.status = status.transitionTo(DocumentStatus.INDEXED);
        this.errorMessage = null;
        touchStateChange();
    }

    /** 解析失败。强制附加 errorMessage。Task 4: 任一中间态 (PARSING/CHUNKED/EMBEDDING/INDEXING) 均可失败。 */
    public void markFailed(String errorMessage) {
        ensureNotDeleted();
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalStateException("markFailed 必须带 errorMessage");
        }
        this.status = status.transitionTo(DocumentStatus.FAILED);
        this.errorMessage = errorMessage;
        touchStateChange();
    }

    /**
     * 重试。Task 4: 上限放宽到 3 次 (原 V1 仅 1 次, 实际生产 Milvus/embedding 偶发抖动需多次重试)。
     *
     * <p>FAILED/INDEXED → PARSING, retryCount++, errorMessage 清空。调 parsingTrigger 重跑整条管道。
     */
    public void retry() {
        ensureNotDeleted();
        if (status != DocumentStatus.FAILED && status != DocumentStatus.INDEXED) {
            throw new IllegalStateException("仅 FAILED / INDEXED 可触发 retry, 当前=" + status);
        }
        if (retryCount >= MAX_RETRY) {
            throw new IllegalStateException(
                    "V10 重试上限 " + MAX_RETRY + " 次, 当前 retryCount=" + retryCount);
        }
        this.status = status.transitionTo(DocumentStatus.PARSING);
        this.retryCount = retryCount + 1;
        this.errorMessage = null;
        touchStateChange();
    }

    /** 软删。仅 INDEXED/FAILED 可删; 任一 in-flight 状态 (PARSING/CHUNKED/EMBEDDING/INDEXING) 不可删。 */
    public void softDelete() {
        if (this.status == DocumentStatus.PARSING
                || this.status == DocumentStatus.CHUNKED
                || this.status == DocumentStatus.EMBEDDING
                || this.status == DocumentStatus.INDEXING) {
            throw new IllegalStateException("in-flight 状态不可删除, 当前=" + status);
        }
        this.deleted = true;
    }

    /**
     * 复活(对称 softDelete)。
     *
     * <p>用于"同 hash 软删 doc 被重新上传"场景: 应用层选择复活老聚合根(保留原 doc_id)而不是 插新 doc, 这样 (a) 不撞
     * documents.uk_content_hash 唯一约束, (b) chunks/Milvus 可走重切路径。 调用方须保证 chunks 已清(reactivate 不负责清旧
     * chunks, 状态机只关心自身)。
     *
     * <p>Task 4: reactivate 是业务复活特权路径, 直接 set status=UPLOADED 不走 {@link #transitionTo} (因为
     * INDEXED/FAILED → UPLOADED 不在常规状态机规则里, 但业务上复活必须能从这里启动)。
     */
    public void reactivate() {
        if (!this.deleted) {
            throw new IllegalStateException("Document 未删除, 无需 reactivate");
        }
        this.deleted = false;
        this.status = DocumentStatus.UPLOADED;
        this.retryCount = 0;
        this.errorMessage = null;
        // P3-2: 复活时清除 pending Milvus delete 标记。
        // 否则 sweeper 可能在 doc 已复活且 upsert 新向量后误删它们 (race)。
        this.pendingMilvusDelete = false;
        touchStateChange();
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

    public String logicalDocumentKey() {
        return logicalDocumentKey;
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
        return (status == DocumentStatus.FAILED || status == DocumentStatus.INDEXED)
                && retryCount < MAX_RETRY;
    }

    /** Task 4: 状态机最后变更时间, reconcile 扫卡死用。 */
    public Instant lastStateChangeAt() {
        return lastStateChangeAt;
    }

    /** Task 4: 内部 hook — 每次状态变更刷新时间戳。 */
    private void touchStateChange() {
        this.lastStateChangeAt = Instant.now();
    }

    /**
     * Task 4: 持久化恢复时反序列化时间戳, 不重新打戳。
     *
     * <p>仅供 {@code DocumentMapper.toDomain} 调用 — 让从 DB 取回的 doc 保留真实时间戳 (reconcile 扫卡死的判定基准)。一旦任何一个
     * {@code mark*} 方法被调用, 后续打戳会由 {@link #touchStateChange} 覆盖。本方法是 package-private 是为减少误用面。
     */
    public void amendLastStateChangeAt(Instant ts) {
        if (ts != null) {
            this.lastStateChangeAt = ts;
        }
    }

    /** 是否为同一逻辑文档的当前可检索版本。字段名保留 isDefault 兼容旧 API。 */
    public boolean isDefault() {
        return isDefault;
    }

    /** 标记为同一逻辑文档的当前版本。唯一性由数据库和应用事务共同保证。 */
    public void markDefault() {
        this.isDefault = true;
    }

    /** Phase 3 / P3-1: 取消默认标记 (set-default 把老的 default 取消时调)。 */
    public void unmarkDefault() {
        this.isDefault = false;
    }

    /**
     * Phase 3 / P3-2: 软删文档后, Milvus 向量删除失败时由 DocumentManageService 调用, 标记 pending=true 让 sweeper
     * 后续重试删除。
     */
    public void markPendingMilvusDelete() {
        this.pendingMilvusDelete = true;
    }

    /** Phase 3 / P3-2: Milvus 删除 (sweeper 重试 / 同步路径) 成功后清除 pending 标记。 */
    public void clearPendingMilvusDelete() {
        this.pendingMilvusDelete = false;
    }

    /** Phase 3 / P3-2: 是否有待清理的 Milvus 向量; sweeper 用此过滤待重试文档。 */
    public boolean pendingMilvusDelete() {
        return pendingMilvusDelete;
    }

    public int activeGeneration() { return activeGeneration; }

    public Integer pendingGeneration() { return pendingGeneration; }

    public void amendGenerationState(int active, Integer pending) {
        if (active < 1) throw new IllegalArgumentException("activeGeneration 必须 >= 1");
        this.activeGeneration = active;
        this.pendingGeneration = pending;
    }

    public void beginGenerationBuild(int generation) {
        ensureNotDeleted();
        if (generation <= activeGeneration) {
            throw new IllegalStateException("新 generation 必须大于 activeGeneration");
        }
        if (pendingGeneration != null && pendingGeneration != generation) {
            throw new IllegalStateException("已有 generation 正在构建: " + pendingGeneration);
        }
        pendingGeneration = generation;
    }

    public void activateGeneration(int generation) {
        ensureNotDeleted();
        if (pendingGeneration == null || pendingGeneration != generation) {
            throw new IllegalStateException("generation 未处于待激活状态: " + generation);
        }
        activeGeneration = generation;
        pendingGeneration = null;
    }

    public void failGenerationBuild(int generation) {
        if (pendingGeneration != null && pendingGeneration == generation) pendingGeneration = null;
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
