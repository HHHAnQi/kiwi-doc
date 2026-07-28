package com.xxx.ragdoc.application.chunk.query;

/** 查询相邻 chunks 的结果(prev / next 可任一为 null, 即跨边界)。 */
public record ChunkNeighbors(ChunkDetail prev, ChunkDetail next) {
    public static ChunkNeighbors empty() {
        return new ChunkNeighbors(null, null);
    }
}
