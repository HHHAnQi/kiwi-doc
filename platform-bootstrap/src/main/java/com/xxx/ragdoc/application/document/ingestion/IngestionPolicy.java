package com.xxx.ragdoc.application.document.ingestion;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.SecurityScannerProperties;
import com.xxx.ragdoc.application.document.security.ScanResult;
import com.xxx.ragdoc.application.document.security.port.SecurityScannerPort;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 同步与异步解析共用的入库安全、脱敏、质量门禁和版本审计策略。 */
@Component
@RequiredArgsConstructor
public class IngestionPolicy {
    public static final String PARSER_VERSION = "tika-2.9.2";
    public static final String CHUNKER_VERSION = "structural-v3";
    public static final String EMBEDDING_VERSION = "bge-m3-1024";

    private final SecurityScannerPort securityScanner;
    private final SecurityScannerProperties securityProperties;
    private final PiiSanitizer piiSanitizer;
    private final IngestionQualityGate qualityGate;
    private final IngestionQualityReportPort reportPort;
    private final ChunkDeduplicator chunkDeduplicator;

    public PreparedText prepareText(Long documentId, String rawText) {
        ScanResult scan = securityScanner.scan(rawText, documentId);
        if (securityProperties.shouldBlock(scan)) {
            throw new IllegalStateException("security_blocked: " + scan.summary());
        }
        PiiSanitizer.Result sanitized = piiSanitizer.sanitize(rawText);
        IngestionQualityGate.Report report =
                qualityGate.validateText(sanitized.text(), sanitized.totalRedactions());
        record(documentId, "TEXT", report);
        qualityGate.requirePassed(report);
        return new PreparedText(sanitized.text(), sanitized.totalRedactions());
    }

    public void validateChunks(Long documentId, List<Chunk> chunks, int redactions) {
        IngestionQualityGate.Report report = qualityGate.validateChunks(chunks, redactions);
        record(documentId, "CHUNK", report);
        qualityGate.requirePassed(report);
    }

    public List<Chunk> deduplicateChunks(Long documentId, List<Chunk> chunks, int redactions) {
        ChunkDeduplicator.Result result = chunkDeduplicator.deduplicate(chunks);
        validateChunks(documentId, result.chunks(), redactions);
        return result.chunks();
    }

    public void validateEmbeddings(
            Long documentId, List<EmbeddingResult> embeddings, int expected, int redactions) {
        IngestionQualityGate.Report report =
                qualityGate.validateEmbeddings(embeddings, expected, redactions);
        record(documentId, "EMBEDDING", report);
        qualityGate.requirePassed(report);
    }

    private void record(Long documentId, String stage, IngestionQualityGate.Report report) {
        reportPort.record(
                documentId, stage, report, PARSER_VERSION, CHUNKER_VERSION, EMBEDDING_VERSION);
    }

    public record PreparedText(String text, int redactionCount) {}
}
