package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 5: Reciprocal Rank Fusion (RRF) 融合器。
 *
 * <p>公式:
 *
 * <pre>
 *   RRFScore(doc) = sum_over_lists  1 / (k + rank_in_list)
 * </pre>
 *
 * 其中 rank 从 1 开始 (第 1 名 → 1/(k+1), 第 2 名 → 1/(k+2))。k 是平滑常数 (默认 60,
 * 与 Milvus RRFRanker 一致, 与 chat/spec.md L65 公式一致)。
 *
 * <p>决策: RRF 只用 rank (位次), 不用原始分数 — 让 BGE dense 余弦分数 (0~1) 与 BM25 (正向无界)
 * 尺度无关; 这是 hybrid retrieval 业界默认做法。
 *
 * <p>用户: {@link HybridRetriever} 调它融合 dense + sparse 两路输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RRFFusioner {

    /**
     * 融合多路 ranked list。
     *
     * @param ranked dense / sparse 等多路按位次降序输入 (每个 list 是排名 1,2,...)
     * @param k RRF 平滑常数, 默认 60
     * @param topK 输出截断数
     */
    public List<ScoredChunk> fuse(List<List<ScoredChunk>> ranked, int k, int topK) {
        if (ranked == null || ranked.isEmpty()) return List.of();
        if (k < 1) k = 60; // 防御

        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Float> top1RawScore = new HashMap<>(); // 仅留首路分数做 log

        for (List<ScoredChunk> one : ranked) {
            if (one == null || one.isEmpty()) continue;
            for (int i = 0; i < one.size(); i++) {
                ScoredChunk s = one.get(i);
                int rank = i + 1; // 从 1 开始
                double delta = 1.0 / (k + rank);
                scores.merge(s.chunkId(), delta, Double::sum);
                // 第一路 (dense) 分数保留为 log/调试; 不参与融合决策
                top1RawScore.putIfAbsent(s.chunkId(), s.score());
            }
        }

        // 按融合分数降序, 取 topK
        List<Long> ordered = new ArrayList<>(scores.keySet());
        ordered.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        List<ScoredChunk> result = new ArrayList<>(Math.min(topK, ordered.size()));
        for (Long cid : ordered) {
            if (result.size() >= topK) break;
            // 输出 score 字段填 RRF 分数 (float), 不再用原 dense/bm25 分数
            result.add(new ScoredChunk(cid, scores.get(cid).floatValue()));
        }
        log.debug(
                "rrf.fused lists={}, k={}, topK={}, output={}",
                ranked.size(),
                k,
                topK,
                result.size());
        return result;
    }
}
