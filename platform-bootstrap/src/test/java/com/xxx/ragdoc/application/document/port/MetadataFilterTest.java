package com.xxx.ragdoc.application.document.port;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-SMOKE-2: {@link VectorStore.MetadataFilter} 值对象不变量。
 *
 * <p>这个值对象是 F1 检索过滤的核心, isEmpty() 决定 Milvus 是否传 expr。任一条件判断错就会导致全表扫描或反向过滤偏差。
 */
@DisplayName("MetadataFilter 值对象")
class MetadataFilterTest {

    @Test
    @DisplayName("empty() 工厂方法: 全部 null, isEmpty=true")
    void emptyShouldBeEmpty() {
        VectorStore.MetadataFilter f = VectorStore.MetadataFilter.empty();
        assertThat(f.isEmpty()).isTrue();
        assertThat(f.source()).isNull();
        assertThat(f.version()).isNull();
        assertThat(f.language()).isNull();
        assertThat(f.tenantId()).isNull();
        assertThat(f.allowedDocIds()).isNull();
    }

    @Test
    @DisplayName("全部字段 blank/null: isEmpty=true (防 Milvus 加空串 expr 导致语法错)")
    void allBlankShouldBeEmpty() {
        assertThat(new VectorStore.MetadataFilter("  ", null, "\t", "  ", null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("任意单个非空(source): isEmpty=false")
    void onlySourceNonEmpty() {
        assertThat(new VectorStore.MetadataFilter("nacos", null, null, null, null).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("任意单个非空(version): isEmpty=false")
    void onlyVersionNonEmpty() {
        assertThat(new VectorStore.MetadataFilter(null, "2.4", null, null, null).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("任意单个非空(language): isEmpty=false")
    void onlyLanguageNonEmpty() {
        assertThat(new VectorStore.MetadataFilter(null, null, "zh", null, null).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("任意单个非空(tenantId): isEmpty=false")
    void onlyTenantNonEmpty() {
        assertThat(new VectorStore.MetadataFilter(null, null, null, "default", null).isEmpty())
                .isFalse();
    }

    @Test
    @DisplayName("任意单个非空(allowedDocIds 集合): isEmpty=false")
    void onlyAllowedDocIdsNonEmpty() {
        assertThat(new VectorStore.MetadataFilter(null, null, null, null, java.util.Set.of(1L, 2L)).isEmpty())
                .isFalse();
    }

    @Test
    @DisplayName("三者全有值: isEmpty=false")
    void allPresent() {
        assertThat(new VectorStore.MetadataFilter("dubbo", "3.0", "zh", null, null).isEmpty())
                .isFalse();
    }
}
