package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chunk.query.PageChunks;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PageChunksResponse")
public record PageChunksResponse(List<ChunkDetailResponse> chunks, int page, int totalPagesInDoc) {
    public static PageChunksResponse from(PageChunks p) {
        List<ChunkDetailResponse> items =
                p.chunks().stream().map(ChunkDetailResponse::from).toList();
        return new PageChunksResponse(items, p.page(), p.totalPagesInDoc());
    }
}
