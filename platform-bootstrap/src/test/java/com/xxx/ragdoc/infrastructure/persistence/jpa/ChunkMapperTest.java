package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChunkEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkMapper} 单测: Q3-B 关键正确性 —— sectionPath(List&lt;String&gt;) ↔ JSON string 双向映射。
 *
 * <p>覆盖点:
 *
 * <ul>
 *   <li>List 有内容 ↔ JSON 数组字符串, 来回不丢元素
 *   <li>空 list / null → entity 字段 null(DB 列 NULL, 向后兼容老 chunks)
 *   <li>entity 字段为 null/"[]"/"null" → domain List.of()(容错)
 *   <li>含中文 heading(典型 corpus 用例)
 * </ul>
 */
@DisplayName("ChunkMapper - Q3-B sectionPath 双向序列化")
class ChunkMapperTest {

    @Test
    @DisplayName("List<String> → entity.sectionPath → domain sectionPath, 往返保真")
    void sectionPathRoundTrip() {
        Chunk original =
                new Chunk(
                        1L,
                        100L,
                        0,
                        ChunkType.TEXT,
                        "正文",
                        0,
                        null,
                        null,
                        "hash",
                        List.of("Dubbo", "异步调用", "异步编程"));

        ChunkEntity entity = ChunkMapper.toNewEntity(original);
        assertThat(entity.getSectionPath()).as("List 应序列化为 JSON 字符串").contains("Dubbo");

        Chunk restored = ChunkMapper.toDomain(entity);
        assertThat(restored.sectionPath())
                .as("JSON 字符串应反序列化回 List, 元素保真")
                .containsExactly("Dubbo", "异步调用", "异步编程");
    }

    @Test
    @DisplayName("空 list / null sectionPath → entity NULL → domain 空 list(向后兼容老 chunks)")
    void nullAndEmptySectionPathRoundTrip() {
        // null
        Chunk nullPath = new Chunk(1L, 1L, 0, ChunkType.TEXT, "x", 0, null, null, "h", null);
        ChunkEntity e1 = ChunkMapper.toNewEntity(nullPath);
        assertThat(e1.getSectionPath()).isNull();
        // readonly entry 回读(模拟老 chunks 数据, section_path 为 NULL)
        e1.setSectionPath(null);
        assertThat(ChunkMapper.toDomain(e1).sectionPath()).isEmpty();

        // 空列表
        Chunk emptyPath = new Chunk(1L, 1L, 0, ChunkType.TEXT, "x", 0, null, null, "h", List.of());
        ChunkEntity e2 = ChunkMapper.toNewEntity(emptyPath);
        assertThat(e2.getSectionPath()).isNull(); // 空 list 也落 NULL

        // 容错: "[]" / "null" / "" / "garbage" 都应解析为空 list
        for (String malformed : new String[] {"[]", "null", "", "garbage", "[unterminated"}) {
            ChunkEntity e = ChunkMapper.toNewEntity(emptyPath);
            e.setSectionPath(malformed);
            assertThat(ChunkMapper.toDomain(e).sectionPath())
                    .as("malformed=%s 应容错为空 list", malformed)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("中文/特殊字符 heading 应能正常序列化往返")
    void chineseHeadingsRoundTrip() {
        List<String> path = List.of("5.4 异步调用", "RpcContext", "《复杂场景》");
        Chunk c = new Chunk(1L, 1L, 0, ChunkType.TEXT, "x", 0, null, null, "h", path);

        ChunkEntity entity = ChunkMapper.toNewEntity(c);
        Chunk restored = ChunkMapper.toDomain(entity);
        assertThat(restored.sectionPath()).containsExactlyElementsOf(path);
    }

    @Test
    @DisplayName("Q3-B fix: 含双引号与反斜杠的 heading 应能往返保真(corpus 真实案例)")
    void headingWithQuotesAndBackslashRoundTrip() {
        // 真实 corpus 案例: 章节标题里有 《Apache Dubbo3 "源码深入解析"》(带嵌套引号)
        // 与 路径 "C:\\nacos\\conf"(配置路径示例, 反斜杠)
        List<String> path = List.of("Apache Dubbo3 \"源码深入解析\"", "配置路径 C:\\nacos\\conf");

        Chunk c = new Chunk(1L, 1L, 0, ChunkType.TEXT, "x", 0, null, null, "h", path);
        ChunkEntity entity = ChunkMapper.toNewEntity(c);

        // 序列化结果必须是合法 JSON(双引号被 escape 为 \", 反斜杠被 escape 为 \\)
        String json = entity.getSectionPath();
        assertThat(json).as("应含转义后的双引号").contains("\\\"");
        assertThat(json).as("应含转义后的反斜杠").contains("\\\\");

        Chunk restored = ChunkMapper.toDomain(entity);
        assertThat(restored.sectionPath())
                .as("含特殊字符的 heading 往返后必须完整保真")
                .containsExactlyElementsOf(path);
    }
}
