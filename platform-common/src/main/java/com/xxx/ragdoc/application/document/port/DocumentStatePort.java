package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.domain.document.Chunk;
import java.util.List;

/**
 * Task 4 / V10 DocLifecycle: 解析管道里把 {@code Document.status} 推进到中间态的端口。
 *
 * <p>同步路径 ({@code TikaParsingTrigger}) 和异步路径 ({@code ParseTaskConsumer} + ParseWorker) 都依赖此端口推进状态机
 * — 它们不感知 infra (JPA), 维持 ArchUnit "application 不依赖 infrastructure" 纪律。 实现见 {@code
 * infrastructure.persistence.jpa.JpaDocumentStateAdapter}。
 *
 * <p>四个方法对应状态机四个推进节点:
 *
 * <ol>
 *   <li>{@link #markChunked(Long, List)} — 切片持久化完成 (PARSING → CHUNKED), 同时回填 chunks 列表
 *   <li>{@link #markEmbedding(Long)} — 调 embedding API 前 (CHUNKED → EMBEDDING)
 *   <li>{@link #markIndexing(Long)} — Milvus upsert 前 (EMBEDDING → INDEXING)
 *   <li>{@link #markIndexed(Long)} — Milvus upsert 成功后 (INDEXING → INDEXED), 检索终态
 * </ol>
 *
 * <p>语义约定: 每个方法都做"短事务 + 状态机迁移 + persist lastStateChangeAt", 调用方不需要再 save。 失败抛 {@link
 * IllegalStateException} (非法迁移) 由调用方决定是否 catch + markFailed。
 */
public interface DocumentStatePort {

    /** PARSING → CHUNKED; chunks 列表回填到聚合根 (后续回查 chunks 表用)。 */
    void markChunked(Long documentId, List<Chunk> chunks);

    /** CHUNKED → EMBEDDING; 调用方即将调 embedding API。 */
    void markEmbedding(Long documentId);

    /** EMBEDDING → INDEXING; 调用方即将调 vectorStore.upsertChunks。 */
    void markIndexing(Long documentId);

    /** INDEXING → INDEXED; Milvus upsert 成功, 文档可问答 (检索终态)。 */
    void markIndexed(Long documentId);
}
