package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.document.query.DocumentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 文档列表项响应 DTO。 */
@Schema(name = "DocumentSummaryResponse")
public record DocumentSummaryResponse(
        Long docId,
        String originalFilename,
        String status,
        long sizeBytes,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt,
        String source,
        String version,
        String logicalDocumentKey,
        String language,
        String docType,
        boolean isDefault,
        boolean pendingMilvusDelete) {
    public static DocumentSummaryResponse from(DocumentSummary s) {
        return new DocumentSummaryResponse(
                s.docId(),
                s.originalFilename(),
                s.status().name(),
                s.sizeBytes(),
                s.chunkCount(),
                s.createdAt(),
                s.updatedAt(),
                s.source(),
                s.version(),
                s.logicalDocumentKey(),
                s.language(),
                s.docType(),
                s.isDefault(),
                s.pendingMilvusDelete());
    }
}
