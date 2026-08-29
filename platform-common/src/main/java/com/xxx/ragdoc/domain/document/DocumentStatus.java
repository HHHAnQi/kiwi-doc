package com.xxx.ragdoc.domain.document;

/**
 * Document 索引生命周期状态机 (Task 4 / V10 DocLifecycle)。
 *
 * <pre>
 *   UPLOADED → PARSING → CHUNKED → EMBEDDING → INDEXING → INDEXED
 *                ↘         ↘          ↘           ↘        ↓
 *                 FAILED ←────────────────────────────── (任一中间态可失败)
 *
 *   FAILED  → PARSING  (应用层手动/自动重试, retry_count++)
 *   INDEXED → PARSING  (重新解析: reactivate / reparse)
 * </pre>
 *
 * <p>状态语义:
 *
 * <ul>
 *   <li>{@link #UPLOADED} — 原始文件已落 MinIO, 等待解析
 *   <li>{@link #PARSING} — Tika 文本抽取进行中
 *   <li>{@link #CHUNKED} — 切片持久化到 chunks 表完成
 *   <li>{@link #EMBEDDING} — 调用 embedding API 中
 *   <li>{@link #INDEXING} — Milvus 向量 upsert 中
 *   <li>{@link #INDEXED} — Milvus 索引完成, 可问答 (检索终态)
 *   <li>{@link #FAILED} — 任一中间步失败
 * </ul>
 *
 * <p>注意: 任务文档列了 DELETED 状态, 但项目采用更安全的「软删标记 deleted_at + status 不变」模型 (reactivate 路径依赖此约定, 把 DELETED
 * 加进 enum 会让 reactivate 跨整个状态机反向迁移, 风险大); 因此本 enum 不含 DELETED, 由 Document.deletedAt 字段承载。
 */
public enum DocumentStatus {
    UPLOADED("原始文件已落 MinIO,等待解析"),
    PARSING("Tika 文本抽取进行中"),
    CHUNKED("切片完成, 已持久化到 chunks 表"),
    EMBEDDING("调用 embedding API 中"),
    INDEXING("Milvus 向量 upsert 中"),
    INDEXED("索引完成, 可问答 (检索终态)"),
    FAILED("解析 / 索引失败");

    private final String description;

    DocumentStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** 状态迁移合法性校验。任何非法迁移抛 IllegalStateException。 */
    public DocumentStatus transitionTo(DocumentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("非法状态迁移: " + this + " → " + target + " 不被允许");
        }
        return target;
    }

    private boolean canTransitionTo(DocumentStatus target) {
        return switch (this) {
                // 入口: 启动解析
            case UPLOADED -> target == DocumentStatus.PARSING || target == DocumentStatus.FAILED;
                // 解析阶段: 切片成功 / 失败
            case PARSING -> target == DocumentStatus.CHUNKED || target == DocumentStatus.FAILED;
                // 切片完成: 进入 embedding / 失败
            case CHUNKED -> target == DocumentStatus.EMBEDDING || target == DocumentStatus.FAILED;
                // Embedding 完成: 进入 indexing / 失败
            case EMBEDDING -> target == DocumentStatus.INDEXING || target == DocumentStatus.FAILED;
                // Indexing 完成: 索引成功 / 失败
            case INDEXING -> target == DocumentStatus.INDEXED || target == DocumentStatus.FAILED;
                // 终态: 重新解析(reactivate / reparse) 或 retry 退回 PARSING
            case INDEXED, FAILED -> target == DocumentStatus.PARSING;
        };
    }
}
