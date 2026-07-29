package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.List;

/**
 * 向量库写入 / 检索端口。 实现: {@code MilvusVectorStore}(infra 层)。
 *
 * <p>V2-A 只用 {@link #upsertChunks} 和 {@link #deleteByDocumentId}; search 方法留给 V2-B
 * (RetrieveService) 调用。
 */
public interface VectorStore {

    /**
     * 批量写入 chunks 的向量(与 chunks 表同序)。
     *
     * @param documentId 所属文档
     * @param chunks 已持久化的 chunks(含 id)
     * @param embeddings 与 chunks 同序的向量(BGE-M3 dense+sparse)
     */
    void upsertChunks(Long documentId, List<Chunk> chunks, List<EmbeddingResult> embeddings);

    /** 删除指定文档的所有向量(重新解析前调用)。 */
    void deleteByDocumentId(Long documentId);

    /**
     * 混合检索(V2-B 用): dense + sparse → RRF 融合 → top-k chunk_ids。
     *
     * @param queryEmbedding 查询向量(BGE-M3 embed 结果)
     * @param docId 可选, 限定文档; null = 跨全库
     * @param topK 返回条数
     * @return 召回的 chunk_id 列表(按融合后分数降序)
     */
    List<Long> search(EmbeddingResult queryEmbedding, Long docId, int topK);
}
