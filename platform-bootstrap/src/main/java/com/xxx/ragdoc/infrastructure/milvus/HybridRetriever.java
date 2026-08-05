package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 5: Hybrid 检索 — Dense + BM25 Sparse → RRF 融合.
 *
 * <p>组合 {@link DenseRetriever} + {@link SparseRetriever} + {@link RRFFusioner}.
 *
 * <p>与 {@link MilvusVectorStore#searchHybrid} 的关系: searchHybrid 把 dense+sparse 一起送 SDK
 * RRFRanker (单次 RPC); HybridRetriever 是 application 层手工融合 (两次 RPC + 本地 RRF 公式),
 * 让 Task 5 AB 实验能独立 manifest dense vs sparse 候选, observe recall.
 *
 * <p>用法: mode=HYBRID 走本类; mode=DENSE 走 {@link DenseRetriever} 直接 (单路无融合)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final DenseRetriever denseRetriever;
    private final SparseRetriever sparseRetriever;
    private final RRFFusioner rrfFusioner;
    private final RetrieveProperties retrieveProps;

    /**
     * 执行 dense + sparse → RRF 融合。
     *
     * @param expr 标量过滤表达式; null = 不过滤
     * @param topK 最终输出数量
     */
    public List<ScoredChunk> search(
            EmbeddingResult embedding, String queryText, String expr, int topK) {
        // 单路先用 candidatePool 倍宽度拉取, 给 RRF 留排序空间
        int candidatePool = Math.max(1, retrieveProps.getCandidatePool());
        int fetchN = topK * candidatePool;
        int k = retrieveProps.getRrf().getK();

        // 单条都不能 fail hybrid 主流程: BM25 路 MilvusVectorStore.searchSparseBM25 失败返空 List,
        // RRF 融合时自然降级 (只剩 dense 路 → 等价 dense 结果)
        List<ScoredChunk> denseHits = denseRetriever.search(embedding, expr, fetchN);
        List<ScoredChunk> sparseHits = sparseRetriever.search(queryText, expr, fetchN);

        List<ScoredChunk> fused = rrfFusioner.fuse(List.of(denseHits, sparseHits), k, topK);
        log.info(
                "hybrid_retriever.done dense={}, sparse={}, fused={}, topK={}, rrf_k={}, pool={}",
                denseHits.size(),
                sparseHits.size(),
                fused.size(),
                topK,
                k,
                candidatePool);
        return fused;
    }
}
