package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 与具体检索引擎无关的确定性 RRF，供 Hybrid 和多 Query 召回共同复用。 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {}

    public static List<ScoredChunk> fuse(List<List<ScoredChunk>> rankedLists, int k, int topK) {
        if (rankedLists == null || rankedLists.isEmpty() || topK < 1) return List.of();
        int normalizedK = k < 1 ? 60 : k;
        Map<Long, Aggregate> aggregates = new HashMap<>();
        for (List<ScoredChunk> ranked : rankedLists) {
            if (ranked == null || ranked.isEmpty()) continue;
            Set<Long> seen = new HashSet<>();
            for (int index = 0; index < ranked.size(); index++) {
                ScoredChunk hit = ranked.get(index);
                if (hit == null || hit.chunkId() == null || !seen.add(hit.chunkId())) continue;
                int rank = index + 1;
                aggregates
                        .computeIfAbsent(hit.chunkId(), ignored -> new Aggregate())
                        .accept(rank, 1d / (normalizedK + rank));
            }
        }
        List<Map.Entry<Long, Aggregate>> ordered = new ArrayList<>(aggregates.entrySet());
        ordered.sort(
                Comparator.<Map.Entry<Long, Aggregate>>comparingDouble(
                                entry -> entry.getValue().score())
                        .reversed()
                        .thenComparing(
                                Comparator.<Map.Entry<Long, Aggregate>>comparingInt(
                                                entry -> entry.getValue().channels())
                                        .reversed())
                        .thenComparingInt(entry -> entry.getValue().bestRank())
                        .thenComparingLong(Map.Entry::getKey));
        return ordered.stream()
                .limit(topK)
                .map(entry -> new ScoredChunk(entry.getKey(), (float) entry.getValue().score()))
                .toList();
    }

    private static final class Aggregate {
        private double score;
        private int channels;
        private int bestRank = Integer.MAX_VALUE;

        void accept(int rank, double contribution) {
            score += contribution;
            channels++;
            bestRank = Math.min(bestRank, rank);
        }

        double score() {
            return score;
        }

        int channels() {
            return channels;
        }

        int bestRank() {
            return bestRank;
        }
    }
}
