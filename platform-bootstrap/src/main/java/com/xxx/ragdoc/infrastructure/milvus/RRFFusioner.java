package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 其中 rank 从 1 开始 (第 1 名 → 1/(k+1), 第 2 名 → 1/(k+2))。k 是平滑常数 (默认 60, 与 Milvus RRFRanker 一致, 与
 * chat/spec.md L65 公式一致)。
 *
 * <p>决策: RRF 只用 rank (位次), 不用原始分数 — 让 BGE dense 余弦分数 (0~1) 与 BM25 (正向无界) 尺度无关; 这是 hybrid retrieval
 * 业界默认做法。
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
        return fuseDetailed(ranked, k, topK).stream()
                .map(result -> new ScoredChunk(result.chunkId(), (float) result.fusedScore()))
                .toList();
    }

    /** 可解释的确定性融合结果。每条召回路内先按 chunkId 去重，防止异常重复候选重复加分； 同分时按命中路数、最佳排名、chunkId 稳定排序，保证回放与线上结果一致。 */
    public List<FusionResult> fuseDetailed(List<List<ScoredChunk>> ranked, int k, int topK) {
        if (ranked == null || ranked.isEmpty() || topK < 1) return List.of();
        if (k < 1) k = 60;

        Map<Long, Double> scores = new HashMap<>();
        Map<Long, List<ChannelContribution>> contributions = new HashMap<>();

        for (int channelIndex = 0; channelIndex < ranked.size(); channelIndex++) {
            List<ScoredChunk> one = ranked.get(channelIndex);
            if (one == null || one.isEmpty()) continue;
            Set<Long> seenInChannel = new HashSet<>();
            for (int i = 0; i < one.size(); i++) {
                ScoredChunk s = one.get(i);
                if (s == null || s.chunkId() == null || !seenInChannel.add(s.chunkId())) {
                    continue;
                }
                int rank = i + 1; // 从 1 开始
                double delta = 1.0 / (k + rank);
                scores.merge(s.chunkId(), delta, Double::sum);
                contributions
                        .computeIfAbsent(s.chunkId(), ignored -> new ArrayList<>())
                        .add(new ChannelContribution(channelIndex, rank, s.score(), delta));
            }
        }

        List<FusionResult> ordered =
                scores.entrySet().stream()
                        .map(
                                entry -> {
                                    List<ChannelContribution> details =
                                            List.copyOf(contributions.get(entry.getKey()));
                                    int bestRank =
                                            details.stream()
                                                    .mapToInt(ChannelContribution::rank)
                                                    .min()
                                                    .orElse(Integer.MAX_VALUE);
                                    return new FusionResult(
                                            entry.getKey(),
                                            entry.getValue(),
                                            details.size(),
                                            bestRank,
                                            details);
                                })
                        .sorted(
                                Comparator.comparingDouble(FusionResult::fusedScore)
                                        .reversed()
                                        .thenComparing(
                                                Comparator.comparingInt(
                                                                FusionResult::matchedChannelCount)
                                                        .reversed())
                                        .thenComparingInt(FusionResult::bestRank)
                                        .thenComparingLong(FusionResult::chunkId))
                        .limit(topK)
                        .toList();

        log.debug(
                "rrf.fused lists={}, k={}, topK={}, output={}",
                ranked.size(),
                k,
                topK,
                ordered.size());
        return ordered;
    }

    public record ChannelContribution(
            int channelIndex, int rank, float rawScore, double rrfContribution) {}

    public record FusionResult(
            Long chunkId,
            double fusedScore,
            int matchedChannelCount,
            int bestRank,
            List<ChannelContribution> contributions) {}
}
