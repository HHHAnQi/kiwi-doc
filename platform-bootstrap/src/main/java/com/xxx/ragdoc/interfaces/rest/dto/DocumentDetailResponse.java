package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.document.query.DocumentDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 文档详情响应 DTO。 */
@Schema(name = "DocumentDetailResponse")
public record DocumentDetailResponse(
        Long docId,
        String originalFilename,
        String mimeType,
        String status,
        long sizeBytes,
        long chunkCount,
        int retryCount,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        String source,
        String version,
        String language,
        String docType,
        boolean isDefault,
        boolean pendingMilvusDelete) {
    public static DocumentDetailResponse from(DocumentDetail d) {
        return new DocumentDetailResponse(
                d.docId(),
                d.originalFilename(),
                d.mimeType(),
                d.status().name(),
                d.sizeBytes(),
                d.chunkCount(),
                d.retryCount(),
                d.errorMessage(),
                d.createdAt(),
                d.updatedAt(),
                d.source(),
                d.version(),
                d.language(),
                d.docType(),
                d.isDefault(),
                d.pendingMilvusDelete());
    }
}
