package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.Collection;
import java.util.List;

/**
 * 向量库写入 / 检索端口。 实现: {@code MilvusVectorStore}(infra 层)。
 *
 * <p>V2-A 只用 {@link #upsertChunks} 和 {@link #deleteByDocumentId}; search 方法留给 V2-B
 * (RetrieveService) 调用。
 *
 * <p>V2-C 升级: search 返回 {@link ScoredChunk}(带 score), 为 hybrid RRF 与未来 Reranker 留出分数透出通道。
 *
 * <p>V3 元数据升级: {@link ChunkMetadata} 把 document 级业务元数据 (source/version/language/docType) 和 chunk 级
 * {@code chunkType} 一起带入向量库, 支撑元数据过滤检索与 Parent-Child。 老调用方走 {@link #upsertChunks(Long, List, List)}
 * 默认实现, 落库为缺省值, 零改动。
 */
public interface VectorStore {

    /**
     * 批量写入 chunks 的向量(与 chunks 表同序)。老接口缺省元数据。
     *
     * <p>实现应委托 {@link #upsertChunks(Long, List, List, ChunkMetadata)}, 传 {@link
     * ChunkMetadata#unknown()}。
     */
    default void upsertChunks(
            Long documentId, List<Chunk> chunks, List<EmbeddingResult> embeddings) {
        upsertChunks(documentId, chunks, embeddings, ChunkMetadata.unknown());
    }

    /**
     * 批量写入 chunks 的向量 + 业务元数据。
     *
     * @param documentId 所属文档
     * @param chunks 已持久化的 chunks(含 id)
     * @param embeddings 与 chunks 同序的向量(BGE-M3 dense)
     * @param metadata 落库到向量库的业务元数据, 用于标量过滤检索
     */
    void upsertChunks(
            Long documentId,
            List<Chunk> chunks,
            List<EmbeddingResult> embeddings,
            ChunkMetadata metadata);

    /** 删除指定文档的所有向量(重新解析前调用)。 */
    void deleteByDocumentId(Long documentId);

    /** 混合检索(V2-C 落地): dense(BGE-M3) + sparse(Milvus 原生 BM25) → RRF 融合 → top-k。 无元数据过滤。 */
    default List<ScoredChunk> search(
            EmbeddingResult queryEmbedding, String queryText, Long docId, int topK) {
        return search(queryEmbedding, queryText, docId, topK, null);
    }

    /**
     * 混合检索(V3): dense(BGE-M3) + sparse(Milvus 原生 BM25) → RRF 融合 → top-k, 支持 {@link MetadataFilter}
     * 标量过滤。
     *
     * @param queryEmbedding 查询向量(BGE-M3 dense embed 结果)
     * @param queryText 查询原文(BM25 sparse 检索用,不能从向量反推)
     * @param docId 可选, 限定文档; null = 跨全库
     * @param topK 返回条数
     * @param filter 可选业务元数据过滤(source/version/language), null = 不过滤
     * @return 召回结果(按融合后分数降序), 含 chunkId 与 score
     */
    List<ScoredChunk> search(
            EmbeddingResult queryEmbedding,
            String queryText,
            Long docId,
            int topK,
            MetadataFilter filter);

    /** 带分数的检索结果 record, 给 RetrieveService / 未来 Reranker 用。 */
    record ScoredChunk(Long chunkId, float score) {}

    /**
     * 业务元数据标量过滤条件, 各字段均可空(逻辑 AND): 实现侧负责转 Milvus expr 或同等机制。
     *
     * <p>V9 RAG-Perm-001 起增加 {@link #tenantId} 与 {@link #allowedDocIds} 两个权限相关字段:
     *
     * <ul>
     *   <li>{@code tenantId} 走标量等值过滤, 保证跨租户文档不被 ANN 召回。
     *   <li>{@code allowedDocIds} 是 PermissionResolver 解析出的"可读文档 id 白名单", 实现侧负责注入
     *       <code>document_id in [...]</code> 表达式; 集合为 <b>null</b> 表示哨兵 = admin, 不加 docId
     *       子句(仍然受 tenantId 约束); 集合为 <b>非空</b> 必须严格加载白名单; 集合为 <b>emptySet</b>
     *       表示"无可读文档", 实现侧应短路返回空结果(由 RetrieveService 提前拦截, 不再落到 Milvus)。
     * </ul>
     *
     * @param source 限定来源组件(dubbo/nacos/seata/rocketmq/sentinel); null/blank = 不限
     * @param version 限定版本; null/blank = 不限
     * @param language 限定语言; null/blank = 不限
     * @param tenantId 限定租户; null/blank = 不限(默认主体走的 default 由调用方塞入)
     * @param allowedDocIds 文档白名单: null = 不限制(admin), 集合 = 仅这些 docId 可被召回
     */
    record MetadataFilter(
            String source,
            String version,
            String language,
            String tenantId,
            Collection<Long> allowedDocIds) {

        /** 是否一个条件都没设(实现侧可据此跳过 expr 拼接)。 */
        public boolean isEmpty() {
            return (source == null || source.isBlank())
                    && (version == null || version.isBlank())
                    && (language == null || language.isBlank())
                    && (tenantId == null || tenantId.isBlank())
                    && allowedDocIds == null;
        }

        public static MetadataFilter empty() {
            return new MetadataFilter(null, null, null, null, null);
        }
    }

    /**
     * 写入向量库所需业务元数据。值对象, 一次构造不可变。
     *
     * @param source 来源组件(dubbo/nacos/seata/rocketmq/sentinel)
     * @param version 版本号, 可空
     * @param language 语言(zh/en)
     * @param docType 文档类型(doc/blog/spec/...)
     * @param chunkType chunk 类型(TEXT/CODE/TABLE/FIGURE/TITLE), P3 Parent-Child 时标 child/parent
     * @param tenantId 文档所属租户 (V9 RAG-Perm-001); null/blank 退化为 "default" 兼容老调用方
     */
    record ChunkMetadata(
            String source,
            String version,
            String language,
            String docType,
            String chunkType,
            String tenantId) {

        /** 老路径元数据缺省值, 保证未注入元数据时向量库可写。 */
        public static ChunkMetadata unknown() {
            return new ChunkMetadata("unknown", null, "zh", "doc", "TEXT", "default");
        }
    }
}
