package com.xxx.ragdoc.application.document.query;

import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.time.Instant;

/**
 * 文档详情(比 Summary 多 error_message / retry_count / mimeType)。
 *
 * @param source V3 业务元数据: 来源组件
 * @param version V3 业务元数据: 版本号, 可空
 * @param language V3 业务元数据: 语言
 * @param docType V3 业务元数据: 文档类型
 */
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
        Instant updatedAt,
        String source,
        String version,
        String language,
        String docType) {}
