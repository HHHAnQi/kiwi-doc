package com.xxx.ragdoc.application.document.query;

import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.time.Instant;

/**
 * 文档列表项(轻量, 不含 chunk_count)。
 *
 * <p>列表分页时摘要返回 + 单条详情时用 {@link DocumentDetail}。
 *
 * @param chunkCount 关联 chunks 统计; V1 parsing stub 始终为 0
 */
public record DocumentSummary(
        Long docId,
        String originalFilename,
        DocumentStatus status,
        long sizeBytes,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt) {}
