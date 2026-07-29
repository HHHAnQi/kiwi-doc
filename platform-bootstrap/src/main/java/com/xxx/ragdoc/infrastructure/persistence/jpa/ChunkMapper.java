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

    /** V2: domain.Chunk → ChunkEntity(新建, 持久化前)。 bbox 序列化为 JSON 数组字符串。 */
    public static ChunkEntity toNewEntity(Chunk c) {
        ChunkEntity e = new ChunkEntity();
        e.setDocumentId(c.documentId());
        e.setSeq(c.seq());
        e.setChunkType(c.type().name());
        e.setContent(c.content());
        e.setPage(c.page());
        e.setBbox(formatBbox(c.bbox()));
        e.setParentChunkId(c.parentChunkId());
        e.setContentHash(c.contentHash());
        return e;
    }

    private static String formatBbox(BoundingBox bbox) {
        if (bbox == null) {
            return null;
        }
        return "[" + bbox.x1() + "," + bbox.y1() + "," + bbox.x2() + "," + bbox.y2() + "]";
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
