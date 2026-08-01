package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.VectorStore;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼 Milvus 标量过滤表达式: document_id + {@link VectorStore.MetadataFilter} 逻辑 AND。
 *
 * <p>抽出为独立工具类(原本是 {@code MilvusVectorStore} 的 private 方法), 便于单测覆盖全路径; 同时 P3 Parent-Child 会扩展加
 * chunk_type 过滤, 独立单元更易维护。
 *
 * <p>实现约定:
 *
 * <ul>
 *   <li>不带任何过滤(docId=null 且 filter 空) → 返回 {@code null}(Milvus 不传 filter = 全表 ANN)
 *   <li>字符串字段值单引号转义防注入(expr 字符串字面量用单引号)
 * </ul>
 */
public final class MilvusFilterExprBuilder {

    private MilvusFilterExprBuilder() {}

    /**
     * 拼接表达式。
     *
     * @param docId 可空; 非空则加 {@code document_id == docId}
     * @param filter 可空; 非空且非 empty 则按字段拼接 source/version/language
     * @return 拼好的 expr; 无任何过滤条件时返回 null
     */
    public static String build(Long docId, VectorStore.MetadataFilter filter) {
        List<String> clauses = new ArrayList<>(4);
        if (docId != null) {
            clauses.add(MilvusCollectionInitializer.FIELD_DOC_ID + " == " + docId);
        }
        if (filter != null && !filter.isEmpty()) {
            appendStringClause(clauses, MilvusCollectionInitializer.FIELD_SOURCE, filter.source());
            appendStringClause(
                    clauses, MilvusCollectionInitializer.FIELD_VERSION, filter.version());
            appendStringClause(
                    clauses, MilvusCollectionInitializer.FIELD_LANGUAGE, filter.language());
        }
        return clauses.isEmpty() ? null : String.join(" and ", clauses);
    }

    private static void appendStringClause(List<String> clauses, String fieldName, String value) {
        if (value == null || value.isBlank()) return;
        clauses.add(fieldName + " == '" + escape(value) + "'");
    }

    /** Milvus expr 字符串字面量单引号转义。 */
    static String escape(String s) {
        return s == null ? "" : s.replace("'", "\\'");
    }
}
