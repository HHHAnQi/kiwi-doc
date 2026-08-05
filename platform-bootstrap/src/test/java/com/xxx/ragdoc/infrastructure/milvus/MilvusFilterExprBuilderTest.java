package com.xxx.ragdoc.infrastructure.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.document.port.VectorStore;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * F-SMOKE-3: {@link MilvusFilterExprBuilder} 全路径单测。
 *
 * <p>这个工具拼的 expr 直接喂给 Milvus search.search, 任何拼接错(null 漏判 / 转义漏 / 多余空格)都会导致检索语法错或语义错。 全路径覆盖防
 * K1 同类 bug 复活。V9 RAG-Perm-001 起追加 tenant_id 与 allowedDocIds (权限白名单) 路径。
 */
@DisplayName("MilvusFilterExprBuilder")
class MilvusFilterExprBuilderTest {

    /** 5 参数 MetadataFilter 构造帮助: 前 3 业务字段 + 2 权限字段。 */
    private static VectorStore.MetadataFilter mf(
            String source, String version, String language) {
        return new VectorStore.MetadataFilter(source, version, language, null, null);
    }

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
            assertThat(MilvusFilterExprBuilder.build(null, mf("  ", "  ", "  "))).isNull();
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
            assertThat(MilvusFilterExprBuilder.build(null, mf("nacos", null, null)))
                    .isEqualTo("source == 'nacos'");
        }

        @Test
        @DisplayName("仅 version: \"version == '2.4'\"")
        void versionOnly() {
            assertThat(MilvusFilterExprBuilder.build(null, mf(null, "2.4", null)))
                    .isEqualTo("version == '2.4'");
        }

        @Test
        @DisplayName("仅 language: \"language == 'zh'\"")
        void languageOnly() {
            assertThat(MilvusFilterExprBuilder.build(null, mf(null, null, "zh")))
                    .isEqualTo("language == 'zh'");
        }
    }

    @Nested
    @DisplayName("组合条件 / AND 拼接")
    class Combine {

        @Test
        @DisplayName("source + version: 用 ' and ' 连接, 无多余空格")
        void sourceAndVersion() {
            assertThat(MilvusFilterExprBuilder.build(null, mf("dubbo", "3.0", null)))
                    .isEqualTo("source == 'dubbo' and version == '3.0'");
        }

        @Test
        @DisplayName("source + version + language: 三条件")
        void threeClauses() {
            assertThat(MilvusFilterExprBuilder.build(null, mf("dubbo", "3.0", "zh")))
                    .isEqualTo("source == 'dubbo' and version == '3.0' and language == 'zh'");
        }

        @Test
        @DisplayName("docId 在前 + 元数据: document_id 始终第一")
        void docIdFirst() {
            assertThat(MilvusFilterExprBuilder.build(500L, mf("sentinel", null, null)))
                    .isEqualTo("document_id == 500 and source == 'sentinel'");
        }
    }

    @Nested
    @DisplayName("空白字段跳过 (防止拼接出空串子句)")
    class SkipBlank {

        @Test
        @DisplayName("source='  ' 应被跳过, 只拼 version")
        void blankSourceSkipped() {
            assertThat(MilvusFilterExprBuilder.build(null, mf("  ", "1.8", null)))
                    .isEqualTo("version == '1.8'");
        }
    }

    @Nested
    @DisplayName("注入防护 (单引号转义)")
    class InjectionGuard {

        @Test
        @DisplayName("source 含单引号: 转义为 \\' 防 expr 注入")
        void sourceWithQuote() {
            assertThat(MilvusFilterExprBuilder.build(null, mf("a'b", null, null)))
                    .isEqualTo("source == 'a\\'b'");
        }

        @Test
        @DisplayName("version 含双引号: 不转义(Milvus 用单引号界定), 原样保留")
        void versionWithDoubleQuote() {
            assertThat(MilvusFilterExprBuilder.build(null, mf(null, "1.8\"x", null)))
                    .isEqualTo("version == '1.8\"x'");
        }

        @Test
        @DisplayName("复合攻击 source=a'b + version=1'2 两处都转义")
        void multiFieldEscape() {
            assertThat(MilvusFilterExprBuilder.build(null, mf("a'b", "1'2", null)))
                    .isEqualTo("source == 'a\\'b' and version == '1\\'2'");
        }
    }

    @Nested
    @DisplayName("V9 RAG-Perm-001: tenant_id + 权限白名单")
    class V9Permission {

        @Test
        @DisplayName("tenant_id: \"tenant_id == 'default'\"")
        void tenantIdOnly() {
            VectorStore.MetadataFilter f =
                    new VectorStore.MetadataFilter(null, null, null, "default", null);
            assertThat(MilvusFilterExprBuilder.build(null, f))
                    .isEqualTo("tenant_id == 'default'");
        }

        @Test
        @DisplayName("allowedDocIds=null (admin 哨兵): 不加 docId 子句")
        void allowedDocIdsNullSkipped() {
            VectorStore.MetadataFilter f =
                    new VectorStore.MetadataFilter(null, null, null, "default", null);
            // 仅 tenant 子句, 不出现 document_id in
            assertThat(MilvusFilterExprBuilder.build(null, f))
                    .doesNotContain("document_id in");
        }

        @Test
        @DisplayName("allowedDocIds 非空集合: document_id in [..]")
        void allowedDocIdsWhitelist() {
            VectorStore.MetadataFilter f =
                    new VectorStore.MetadataFilter(null, null, null, "default", Set.of(10L, 20L, 30L));
            String expr = MilvusFilterExprBuilder.build(null, f);
            assertThat(expr).contains("tenant_id == 'default'");
            assertThat(expr).contains("document_id in [");
            assertThat(expr).contains("10").contains("20").contains("30");
        }

        @Test
        @DisplayName("allowedDocIds 空集合 (无可读文档): 永假表达式 (1 == 0)")
        void allowedDocIdsEmptyYieldsFalse() {
            VectorStore.MetadataFilter f =
                    new VectorStore.MetadataFilter(null, null, null, "default", java.util.Collections.emptySet());
            assertThat(MilvusFilterExprBuilder.build(null, f))
                    .contains(MilvusFilterExprBuilder.ALWAYS_FALSE);
        }
    }
}
