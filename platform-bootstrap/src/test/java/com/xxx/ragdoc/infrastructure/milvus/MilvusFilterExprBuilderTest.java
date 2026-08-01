package com.xxx.ragdoc.infrastructure.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.document.port.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * F-SMOKE-3: {@link MilvusFilterExprBuilder} 全路径单测。
 *
 * <p>这个工具拼的 expr 直接喂给 Milvus search.search, 任何拼接错(null 漏判 / 转义漏 / 多余空格)都会导致检索语法错或语义错。 全路径覆盖防 K1 同类
 * bug 复活。
 */
@DisplayName("MilvusFilterExprBuilder")
class MilvusFilterExprBuilderTest {

    @Nested
    @DisplayName("空 / null 路径")
    class EmptyPaths {

        @Test
        @DisplayName("docId=null + filter=null → 返回 null (Milvus 全表 ANN)")
        void allNullReturnsNull() {
            assertThat(MilvusFilterExprBuilder.build(null, null)).isNull();
        }

        @Test
        @DisplayName("docId=null + filter=empty() → 返回 null")
        void emptyFilterReturnsNull() {
            assertThat(MilvusFilterExprBuilder.build(null, VectorStore.MetadataFilter.empty()))
                    .isNull();
        }

        @Test
        @DisplayName("docId=null + filter 全 blank 字段 → 返回 null")
        void blankFieldsReturnNull() {
            assertThat(
                            MilvusFilterExprBuilder.build(
                                    null, new VectorStore.MetadataFilter("  ", "  ", "  ")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("单条件路径")
    class SingleClause {

        @Test
        @DisplayName("仅 docId: 'document_id == 100'")
        void docIdOnly() {
            assertThat(MilvusFilterExprBuilder.build(100L, null)).isEqualTo("document_id == 100");
        }

        @Test
        @DisplayName("仅 source: \"source == 'nacos'\"")
        void sourceOnly() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("nacos", null, null));
            assertThat(expr).isEqualTo("source == 'nacos'");
        }

        @Test
        @DisplayName("仅 version: \"version == '2.4'\"")
        void versionOnly() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter(null, "2.4", null));
            assertThat(expr).isEqualTo("version == '2.4'");
        }

        @Test
        @DisplayName("仅 language: \"language == 'zh'\"")
        void languageOnly() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter(null, null, "zh"));
            assertThat(expr).isEqualTo("language == 'zh'");
        }
    }

    @Nested
    @DisplayName("组合条件 / AND 拼接")
    class Combine {

        @Test
        @DisplayName("source + version: 用 ' and ' 连接, 无多余空格")
        void sourceAndVersion() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("dubbo", "3.0", null));
            assertThat(expr).isEqualTo("source == 'dubbo' and version == '3.0'");
        }

        @Test
        @DisplayName("source + version + language: 三条件")
        void threeClauses() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("dubbo", "3.0", "zh"));
            assertThat(expr)
                    .isEqualTo("source == 'dubbo' and version == '3.0' and language == 'zh'");
        }

        @Test
        @DisplayName("docId 在前 + 元数据: document_id 始终第一")
        void docIdFirst() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            500L, new VectorStore.MetadataFilter("sentinel", null, null));
            assertThat(expr).isEqualTo("document_id == 500 and source == 'sentinel'");
        }
    }

    @Nested
    @DisplayName("空白字段跳过 (防止拼接出空串子句)")
    class SkipBlank {

        @Test
        @DisplayName("source='  ' 应被跳过, 只拼 version")
        void blankSourceSkipped() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("  ", "1.8", null));
            assertThat(expr).isEqualTo("version == '1.8'");
        }
    }

    @Nested
    @DisplayName("注入防护 (单引号转义)")
    class InjectionGuard {

        @Test
        @DisplayName("source 含单引号: 转义为 \\' 防 expr 注入")
        void sourceWithQuote() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("a'b", null, null));
            // 转义后: source == 'a\'b', 而不是: source == 'a'b' (语法错/注入)
            assertThat(expr).isEqualTo("source == 'a\\'b'");
        }

        @Test
        @DisplayName("version 含双引号: 不转义(Milvus 用单引号界定), 原样保留")
        void versionWithDoubleQuote() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter(null, "1.8\"x", null));
            assertThat(expr).isEqualTo("version == '1.8\"x'");
        }

        @Test
        @DisplayName("复合攻击 source=a'b + version=1'2 两处都转义")
        void multiFieldEscape() {
            String expr =
                    MilvusFilterExprBuilder.build(
                            null, new VectorStore.MetadataFilter("a'b", "1'2", null));
            assertThat(expr).isEqualTo("source == 'a\\'b' and version == '1\\'2'");
        }
    }
}
