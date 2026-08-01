package com.xxx.ragdoc.application.chat.port;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;

/**
 * Reranker 端口(V2 第③段: cross-encoder 精排)。
 *
 * <p>实现: {@code BgeRerankClient}(infra 层, 调 text-embeddings-inference 的 /rerank)。
 *
 * <p>设计原则: 输入 (query + candidate docs), 输出按 cross-encoder 分数重排后的 top-N。 端口方法失败抛 异常(由
 * RetrieveService 决策降级到 rerank 前的 hybrid 序)。
 *
 * <p>切 rerank 模型: 新增 adapter (CohereRerankClient / JinaRerankClient 等) 即可, application 层无感知。
 */
public interface RerankClient {

    /**
     * 用 cross-encoder 对候选 (chunkId, text) 对重新打分排序。
     *
     * @param query 用户原始 query(与召回 query 同)
     * @param candidates 候选列表: 每项含 chunkId 和原文(cross-encoder 需要 query+doc 拼接输入)
     * @param topN 最终保留的条数
     * @return 按 reranker 分数降序的 top-N ScoredChunk(score 已替换为 reranker 分数); 输入空则返回空列表
     * @throws Exception reranker 服务调用失败, 由调用方降级到原 hybrid 序
     */
    List<ScoredChunk> rerank(String query, List<RerankCandidate> candidates, int topN)
            throws Exception;

    /** Rerank 输入项: chunkId(回溯用) + text(喂 cross-encoder 用)。 */
    record RerankCandidate(long chunkId, String text) {}
}
