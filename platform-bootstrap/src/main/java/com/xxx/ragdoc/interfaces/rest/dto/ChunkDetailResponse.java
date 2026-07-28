package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chunk.query.ChunkDetail;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChunkDetailResponse")
public record ChunkDetailResponse(
        Long id,
        Long documentId,
        int seq,
        String chunkType,
        String content,
        int page,
        double[] bbox,
        Long parentChunkId,
        String contentHash,
        String documentFilename) {
    public static ChunkDetailResponse from(ChunkDetail d) {
        return new ChunkDetailResponse(
                d.id(),
                d.documentId(),
                d.seq(),
                d.type().name(),
                d.content(),
                d.page(),
                d.bbox(),
                d.parentChunkId(),
                d.contentHash(),
                d.documentFilename());
    }
}
