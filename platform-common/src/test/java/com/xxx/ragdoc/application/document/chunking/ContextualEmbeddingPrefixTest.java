package com.xxx.ragdoc.application.document.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** P1 Contextual Retrieval 前缀构造器单测。 */
@DisplayName("ContextualEmbeddingPrefix 上下文前缀")
class ContextualEmbeddingPrefixTest {

    @Test
    @DisplayName("完整元数据 → 前缀含 来源/文档/章节, 末尾换行衔接 chunk 原文")
    void fullMetadata() {
        String p =
                ContextualEmbeddingPrefix.build(
                        "dubbo-user-guide.pdf", "dubbo", List.of("Dubbo", "异步调用", "异步编程"), 120);
        assertThat(p).isEqualTo("[来源: dubbo | 文档: dubbo-user-guide | 章节: Dubbo › 异步调用 › 异步编程]\n");
        // 与 chunk 原文拼接后即 embed 输入
        assertThat(p + "默认端口是 10911").contains("]\n默认端口");
    }

    @Test
    @DisplayName("无任何元数据 → 空前缀(baseline 行为)")
    void noMetadataReturnsEmpty() {
        assertThat(ContextualEmbeddingPrefix.build(null, "unknown", List.of(), 120)).isEmpty();
        assertThat(ContextualEmbeddingPrefix.build("  ", null, null, 120)).isEmpty();
    }

    @Test
    @DisplayName("仅 sectionPath → 前缀只含章节")
    void sectionOnly() {
        String p = ContextualEmbeddingPrefix.build(null, null, List.of("Nacos", "配置中心"), 120);
        assertThat(p).isEqualTo("[章节: Nacos › 配置中心]\n");
    }

    @Test
    @DisplayName("超长前缀 → 截断到 maxChars")
    void truncatesToMaxChars() {
        String p =
                ContextualEmbeddingPrefix.build(
                        "f.pdf", "dubbo", List.of("a", "b", "c", "d", "e", "f", "g"), 20);
        assertThat(p.length()).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("maxChars<=0 → 空前缀(等价关闭)")
    void disabledByMaxChars() {
        assertThat(ContextualEmbeddingPrefix.build("f.pdf", "dubbo", List.of("a"), 0)).isEmpty();
    }

    @Test
    @DisplayName("文件名扩展名剥离, 防御 '.gitignore' 型名字")
    void stripsExtensionSafely() {
        assertThat(ContextualEmbeddingPrefix.build("a.b.md", null, null, 120))
                .isEqualTo("[文档: a.b]\n");
        assertThat(ContextualEmbeddingPrefix.build(".gitignore", null, null, 120))
                .isEqualTo("[文档: .gitignore]\n");
    }
}
