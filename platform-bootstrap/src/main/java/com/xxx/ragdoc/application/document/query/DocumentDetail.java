package com.xxx.ragdoc.application.document.query;

import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.time.Instant;

/** 文档详情(比 Summary 多 error_message / retry_count / mimeType)。 */
public record DocumentDetail(
        Long docId,
        String originalFilename,
        String mimeType,
        DocumentStatus status,
        long sizeBytes,
        long chunkCount,
        int retryCount,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {}
