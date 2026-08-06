package com.xxx.ragdoc.application.chat.tool;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * PR-4 / EMS-PR4: document_fetch Tool 的输入。三种 fetch 方式可组合: byChunk / byDirection (邻居) / byParent。
 *
 * <p>禁止字段: tenantId / userId / docTenant / path / objectKey 等 — ToolExecutor 扫描拒绝。
 */
public record DocumentFetchInput(
        Long chunkId,
        Long documentId,
        FetchDirection direction,
        @Min(0) @Max(5) Integer neighborCount,
        boolean includeParent)
        implements ToolInput {

    public DocumentFetchInput {
        if (chunkId == null && documentId == null) {
            throw new IllegalArgumentException("chunkId 或 documentId 至少一个必填");
        }
        if (neighborCount != null && (neighborCount < 0 || neighborCount > 5)) {
            throw new IllegalArgumentException("neighborCount 必须在 [0, 5]");
        }
        direction = direction == null ? FetchDirection.SELF : direction;
    }

    /** Convenience: 按 chunkId 取自身。 */
    public DocumentFetchInput(Long chunkId) {
        this(chunkId, null, FetchDirection.SELF, 0, false);
    }

    @Override
    public String normalizedForDedup() {
        StringBuilder sb = new StringBuilder("docFetch|");
        sb.append("chunkId=").append(chunkId).append('|');
        sb.append("documentId=").append(documentId).append('|');
        sb.append("dir=").append(direction.name()).append('|');
        sb.append("n=").append(neighborCount == null ? 0 : neighborCount).append('|');
        sb.append("parent=").append(includeParent);
        return sb.toString();
    }

    public enum FetchDirection {
        SELF,
        NEXT,
        PREV,
        BOTH
    }
}
