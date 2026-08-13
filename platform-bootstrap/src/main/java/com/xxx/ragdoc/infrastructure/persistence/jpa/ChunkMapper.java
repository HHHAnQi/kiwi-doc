package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.domain.document.BoundingBox;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChunkEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * domain.Chunk ↔ ChunkEntity 翻译器。
 *
 * <p>bbox 在 entity 用 String(JSON), 在 domain 用 {@link BoundingBox} 值对象。 V1 简化: JSON 直接 toString /
 * parse, 不引入 Jackson; V2 接真实解析后再上 ObjectMapper。
 *
 * <p>Q3-B: section_path 同样用 JSON 数组字符串, 在 domain 用 {@code List<String>}。沿用现有 bbox 的简单 split/join
 * 风格, 不引入 Jackson。空 list / null → entity 字段为 null。
 */
public final class ChunkMapper {

    private ChunkMapper() {}

    public static Chunk toDomain(ChunkEntity e) {
        BoundingBox bbox = parseBbox(e.getBbox());
        return new Chunk(
                e.getId(),
                e.getDocumentId(),
                e.getGeneration(),
                e.getSeq(),
                ChunkType.valueOf(e.getChunkType()),
                e.getContent(),
                e.getPage(),
                bbox,
                e.getParentChunkId(),
                e.getContentHash(),
                parseStringList(e.getSectionPath()));
    }

    /** V2: domain.Chunk → ChunkEntity(新建, 持久化前)。 bbox 序列化为 JSON 数组字符串。 */
    public static ChunkEntity toNewEntity(Chunk c) {
        ChunkEntity e = new ChunkEntity();
        e.setDocumentId(c.documentId());
        e.setGeneration(c.generation());
        e.setSeq(c.seq());
        e.setChunkType(c.type().name());
        e.setContent(c.content());
        e.setPage(c.page());
        e.setBbox(formatBbox(c.bbox()));
        e.setParentChunkId(c.parentChunkId());
        e.setContentHash(c.contentHash());
        e.setSectionPath(formatStringList(c.sectionPath()));
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

    /**
     * Q3-B: List&lt;String&gt; → JSON 数组字符串。空 list 或 null 返回 null(让 DB 字段为 NULL, 与 V4 schema 向后兼容)。
     *
     * <p>元素做最小 JSON escape: 双引号 → {@code \"}, 反斜杠 → {@code \\}。这两类是 JSON 字符串必转的字符; control char 在
     * heading 文本里不存在(已是 markdown 抽出的可打印文本), 故不全转, 避免 over-engineering。
     *
     * <p>早期版本注释声明"heading 不会有这些字符", 但 corpus 实测有形如 《Dubbo "源码解析"》的带引号章节标题 —— 不 escape 会写出非法 JSON,
     * MySQL JSON 列写入失败或 parseStringList 容错回空 list 造成数据静默丢失。
     */
    private static String formatStringList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(list.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    /** 最小 JSON escape: 顺序敏感(先转 \\ 再转 \"), 防止 double-replace 自我污染。 */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Q3-B: JSON 数组字符串 → List&lt;String&gt;。容错: null/空/"[]"/解析失败 均返回空 list。
     *
     * <p>支持 {@code ["a","b","c"]} 形态, 含转义双引号/反斜杠的元素(如 {@code ["a\"b","c\\d"]})。 状态机扫描, 不引 Jackson
     * 保持架构一致(与 bbox 处理风格对齐)。
     */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String inner = json.trim();
        if (inner.equals("[]") || inner.equals("null")) {
            return List.of();
        }
        if (!inner.startsWith("[") || !inner.endsWith("]")) {
            return List.of();
        }
        // 状态机扫描 "...." 字符串元素。逐字符前进, 遇 \\ 按 escape 处理, 遇 " 收一个元素。
        // 区别于旧正则方案: 能正确识别 \" 不作为字符串结束符。cur 已是 unescape 后形式, add 时不再二次转义。
        List<String> result = new ArrayList<>();
        int i = 1; // 跳过 [
        int n = inner.length() - 1; // 不含最后 ]
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        while (i < n) {
            char c = inner.charAt(i);
            if (!inStr) {
                if (c == '"') {
                    inStr = true;
                    cur.setLength(0);
                }
                // 其他字符(逗号/空白/冒号等)在字符串外都忽略
            } else {
                if (c == '\\' && i + 1 < n) {
                    // escape: \" → ", \\ → \  (JSON 规范的其它 \n \t 等在 heading 不会出现, 简化处理)
                    char next = inner.charAt(i + 1);
                    cur.append(next);
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    result.add(cur.toString());
                    inStr = false;
                } else {
                    cur.append(c);
                }
            }
            i++;
        }
        return result;
    }
}
