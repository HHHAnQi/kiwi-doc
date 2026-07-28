package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.domain.document.BoundingBox;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChunkEntity;

/**
 * domain.Chunk ↔ ChunkEntity 翻译器。
 *
 * <p>bbox 在 entity 用 String(JSON), 在 domain 用 {@link BoundingBox} 值对象。 V1 简化: JSON 直接 toString /
 * parse, 不引入 Jackson; V2 接真实解析后再上 ObjectMapper。
 */
public final class ChunkMapper {

    private ChunkMapper() {}

    public static Chunk toDomain(ChunkEntity e) {
        BoundingBox bbox = parseBbox(e.getBbox());
        return new Chunk(
                e.getId(),
                e.getDocumentId(),
                e.getSeq(),
                ChunkType.valueOf(e.getChunkType()),
                e.getContent(),
                e.getPage(),
                bbox,
                e.getParentChunkId(),
                e.getContentHash());
    }

    /**
     * V1 简化的 bbox 解析。
     *
     * <p>预期格式 {@code [x1, y1, x2, y2]}(JSON 数组)。
     *
     * <p>解析失败返回 null(向前兼容 V1 stub 可能不产 bbox)。
     */
    private static BoundingBox parseBbox(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String trimmed = json.replaceAll("[\\[\\]\\s]", "");
            String[] parts = trimmed.split(",");
            if (parts.length != 4) {
                return null;
            }
            return new BoundingBox(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]));
        } catch (Exception ex) {
            return null;
        }
    }
}
