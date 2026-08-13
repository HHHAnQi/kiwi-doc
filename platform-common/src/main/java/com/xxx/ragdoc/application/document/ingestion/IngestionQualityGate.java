package com.xxx.ragdoc.application.document.ingestion;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 离线入库质量门禁：解析文本、Chunk 清单和 Embedding 必须全部通过才允许进入索引终态。 */
@Component
public class IngestionQualityGate {
    public static final int EXPECTED_DENSE_DIMENSION = 1024;

    public Report validateText(String text, int redactions) {
        List<String> reasons = new ArrayList<>();
        if (text == null || text.isBlank()) reasons.add("EMPTY_TEXT");
        if (text != null && replacementRatio(text) > 0.05) reasons.add("GARBLED_TEXT");
        return report(reasons, 0, 0, redactions);
    }

    public Report validateChunks(List<Chunk> chunks, int redactions) {
        List<String> reasons = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) reasons.add("EMPTY_CHUNKS");
        Set<String> hashes = new HashSet<>();
        int duplicates = 0;
        if (chunks != null) {
            for (Chunk chunk : chunks) {
                if (!hashes.add(chunk.contentHash())) duplicates++;
                if (chunk.content().length() > 20_000) reasons.add("OVERSIZED_CHUNK");
            }
        }
        if (chunks != null && duplicates > Math.max(2, chunks.size() / 5)) {
            reasons.add("EXCESSIVE_DUPLICATE_CHUNKS");
        }
        return report(reasons, chunks == null ? 0 : chunks.size(), 0, redactions);
    }

    public Report validateEmbeddings(List<EmbeddingResult> embeddings, int expected, int redactions) {
        List<String> reasons = new ArrayList<>();
        if (embeddings == null || embeddings.size() != expected) reasons.add("EMBEDDING_COUNT_MISMATCH");
        if (embeddings != null) {
            for (EmbeddingResult embedding : embeddings) {
                float[] vector = embedding == null ? null : embedding.denseVector();
                if (vector == null || vector.length != EXPECTED_DENSE_DIMENSION) {
                    reasons.add("EMBEDDING_DIMENSION_INVALID");
                    continue;
                }
                double norm = 0;
                boolean finite = true;
                for (float value : vector) {
                    finite &= Float.isFinite(value);
                    norm += value * value;
                }
                if (!finite) reasons.add("EMBEDDING_NON_FINITE");
                if (norm == 0) reasons.add("EMBEDDING_ZERO_VECTOR");
            }
        }
        return report(reasons, expected, embeddings == null ? 0 : embeddings.size(), redactions);
    }

    public void requirePassed(Report report) {
        if (!report.passed()) throw new QualityRejectedException(report);
    }

    private static Report report(List<String> reasons, int chunks, int embeddings, int redactions) {
        List<String> distinct = reasons.stream().distinct().toList();
        double score = Math.max(0.0, 1.0 - distinct.size() * 0.2);
        return new Report(distinct.isEmpty(), score, distinct, chunks, embeddings, redactions);
    }

    private static double replacementRatio(String text) {
        if (text.isEmpty()) return 0;
        return text.chars().filter(c -> c == 0xfffd).count() / (double) text.length();
    }

    public record Report(boolean passed, double score, List<String> reasons, int chunkCount,
                         int embeddingCount, int redactionCount) {}

    public static final class QualityRejectedException extends RuntimeException {
        private final Report report;
        public QualityRejectedException(Report report) {
            super("入库质量门禁拒绝: " + String.join(",", report.reasons()));
            this.report = report;
        }
        public Report report() { return report; }
    }
}
