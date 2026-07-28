package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.chunk.ChunkQueryService;
import com.xxx.ragdoc.application.chunk.query.ChunkDetail;
import com.xxx.ragdoc.application.chunk.query.ChunkNeighbors;
import com.xxx.ragdoc.application.chunk.query.PageChunks;
import com.xxx.ragdoc.interfaces.rest.dto.ChunkDetailResponse;
import com.xxx.ragdoc.interfaces.rest.dto.ChunkNeighborsResponse;
import com.xxx.ragdoc.interfaces.rest.dto.PageChunksResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Chunk REST 接口(只读: 单条 / 相邻 / 按页)。 契约以 api-contracts.md §C 为单一事实源。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Chunk", description = "引用详情查看")
public class ChunkController {

    private final ChunkQueryService chunkQueryService;

    @GetMapping("/api/v1/chunks/{id}")
    @Operation(summary = "查 chunk 详情")
    public ChunkDetailResponse getChunk(@PathVariable Long id) {
        ChunkDetail d = chunkQueryService.getChunk(id);
        return ChunkDetailResponse.from(d);
    }

    @GetMapping("/api/v1/chunks/{id}/neighbors")
    @Operation(summary = "查相邻 chunks(direction=prev/next/both, 默认 both)")
    public ChunkNeighborsResponse getNeighbors(
            @PathVariable Long id, @RequestParam(defaultValue = "both") String direction) {
        ChunkQueryService.Direction dir = ChunkQueryService.Direction.parse(direction);
        ChunkNeighbors n = chunkQueryService.getNeighbors(id, dir);
        return ChunkNeighborsResponse.from(n);
    }

    @GetMapping("/api/v1/documents/{id}/chunks")
    @Operation(summary = "按页拉取 chunks")
    public PageChunksResponse listByPage(
            @PathVariable Long id, @RequestParam(defaultValue = "1") int page) {
        PageChunks p = chunkQueryService.listByPage(id, page);
        return PageChunksResponse.from(p);
    }
}
