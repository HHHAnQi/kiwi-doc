package com.xxx.ragdoc.application.chunk.query;

import java.util.List;

/**
 * 按页拉取 chunks 的结果。
 *
 * @param totalPagesInDoc 所属 doc 总页数; V1 在无 chunks 时返回 0
 */
public record PageChunks(List<ChunkDetail> chunks, int page, int totalPagesInDoc) {
    public static PageChunks empty(int page) {
        return new PageChunks(List.of(), page, 0);
    }
}
