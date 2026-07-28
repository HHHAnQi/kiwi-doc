package com.xxx.ragdoc.application.chunk.query;

import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;

/**
 * 单条 chunk 详情(已关联父 doc 元信息, 减少前端二次请求)。
 *
 * @param documentFilename 所属文档原名(仅展示用)
 */
public record ChunkDetail(
        Long id,
        Long documentId,
        int seq,
        ChunkType type,
        String content,
        int page,
        double[] bbox,
        Long parentChunkId,
        String contentHash,
        String documentFilename) {
    /** 从 {@link Chunk} 转换; bbox 拆为数组便于 JSON 序列化。 */
    public static ChunkDetail from(Chunk c, String documentFilename) {
        double[] bbox =
                c.bbox() == null
                        ? null
                        : new double[] {c.bbox().x1(), c.bbox().y1(), c.bbox().x2(), c.bbox().y2()};
        return new ChunkDetail(
                c.id(),
                c.documentId(),
                c.seq(),
                c.type(),
                c.content(),
                c.page(),
                bbox,
                c.parentChunkId(),
                c.contentHash(),
                documentFilename);
    }
}
