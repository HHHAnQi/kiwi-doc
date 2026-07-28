package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chunk.query.ChunkNeighbors;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChunkNeighborsResponse")
public record ChunkNeighborsResponse(ChunkDetailResponse prev, ChunkDetailResponse next) {
    public static ChunkNeighborsResponse from(ChunkNeighbors n) {
        ChunkDetailResponse prev = n.prev() == null ? null : ChunkDetailResponse.from(n.prev());
        ChunkDetailResponse next = n.next() == null ? null : ChunkDetailResponse.from(n.next());
        return new ChunkNeighborsResponse(prev, next);
    }
}
