package com.xxx.ragdoc.application.document.ingestion;

import com.xxx.ragdoc.domain.document.Chunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Chunk 精确 + SimHash 近似去重，阻止模板段和跨页重复内容污染索引。 */
@Component
public class ChunkDeduplicator {
    private static final int MAX_RECENT_COMPARISONS = 256;
    private static final int MAX_HAMMING_DISTANCE = 3;

    public Result deduplicate(List<Chunk> input) {
        if (input == null || input.isEmpty()) return new Result(List.of(), 0, 0);
        List<Chunk> unique = new ArrayList<>();
        List<Long> recentFingerprints = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        int exact = 0;
        int near = 0;
        for (Chunk chunk : input) {
            if (!hashes.add(chunk.contentHash())) {
                exact++;
                continue;
            }
            long fingerprint = simHash(chunk.content());
            boolean similar =
                    recentFingerprints.stream()
                            .skip(Math.max(0, recentFingerprints.size() - MAX_RECENT_COMPARISONS))
                            .anyMatch(
                                    prior ->
                                            Long.bitCount(prior ^ fingerprint)
                                                    <= MAX_HAMMING_DISTANCE);
            if (similar) {
                near++;
                continue;
            }
            int seq = unique.size();
            unique.add(
                    new Chunk(
                            chunk.id(),
                            chunk.documentId(),
                            seq,
                            chunk.type(),
                            chunk.content(),
                            chunk.page(),
                            chunk.bbox(),
                            chunk.parentChunkId(),
                            chunk.contentHash(),
                            chunk.sectionPath()));
            recentFingerprints.add(fingerprint);
        }
        return new Result(List.copyOf(unique), exact, near);
    }

    static long simHash(String text) {
        int[] weights = new int[64];
        String normalized = text == null ? "" : text.toLowerCase().replaceAll("\\s+", " ");
        if (normalized.length() < 3) normalized = normalized + "  ";
        for (int i = 0; i <= normalized.length() - 3; i++) {
            long hash = fnv1a64(normalized.substring(i, i + 3));
            for (int bit = 0; bit < 64; bit++) weights[bit] += ((hash >>> bit) & 1L) == 1 ? 1 : -1;
        }
        long result = 0;
        for (int bit = 0; bit < 64; bit++) if (weights[bit] >= 0) result |= 1L << bit;
        return result;
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public record Result(List<Chunk> chunks, int exactDuplicates, int nearDuplicates) {}
}
