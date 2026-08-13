package com.xxx.ragdoc.domain.document;

import java.util.List;

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
 *   <li>section_path(V2-P4/Q3-B): 该 chunk 所属的 markdown heading 路径栈, 如 ["Dubbo","异步调用","异步编程"]; 无
 *       heading 上下文时为 null 或空 list。
 * </ul>
 */
public record Chunk(
        Long id,
        Long documentId,
        int generation,
        int seq,
        ChunkType type,
        String content,
        int page,
        BoundingBox bbox,
        Long parentChunkId,
        String contentHash,
        List<String> sectionPath) {
    public Chunk(
            Long id, Long documentId, int seq, ChunkType type, String content, int page,
            BoundingBox bbox, Long parentChunkId, String contentHash, List<String> sectionPath) {
        this(id, documentId, 1, seq, type, content, page, bbox, parentChunkId, contentHash, sectionPath);
    }

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
        // sectionPath null 归一为空 list, 让下游统一用 isEmpty() 判断
        if (sectionPath == null) {
            sectionPath = List.of();
        }
    }

    public Chunk withGeneration(int value) {
        return new Chunk(id, documentId, value, seq, type, content, page, bbox,
                parentChunkId, contentHash, sectionPath);
    }
}
