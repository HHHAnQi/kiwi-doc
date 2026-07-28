package com.xxx.ragdoc.domain.document;

/**
 * Chunk 实体(Document 聚合内部,V1 尚未有解析逻辑,仅占位)。
 *
 * <p>V1 设计:
 *
 * <ul>
 *   <li>不可变值对象(构造后字段不可改);
 *   <li>seq 标识同一 Document 内的顺序;
 *   <li>parent_chunk_id 留给 V2 Parent-Child 切片;
 *   <li>{@link ChunkType#FIGURE} 情况下,V1 不带 caption,V2 接 VLM 后补。
 * </ul>
 */
public record Chunk(
        Long id,
        Long documentId,
        int seq,
        ChunkType type,
        String content,
        int page,
        BoundingBox bbox,
        Long parentChunkId,
        String contentHash) {
    public Chunk {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("chunk content 不能为空");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page 不能为负");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq 不能为负");
        }
    }
}
