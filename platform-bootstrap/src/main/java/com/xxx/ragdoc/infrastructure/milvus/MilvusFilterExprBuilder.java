package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.VectorStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 拼 Milvus 标量过滤表达式: document_id + {@link VectorStore.MetadataFilter} + V9 权限白名单 逻辑 AND。
 *
 * <p>抽出为独立工具类(原本是 {@code MilvusVectorStore} 的 private 方法), 便于单测覆盖全路径; 同时 P3 Parent-Child 会扩展加
 * chunk_type 过滤, 独立单元更易维护。
 *
 * <p>实现约定:
 *
 * <ul>
 *   <li>不带任何过滤(docId=null 且 filter 空) → 返回 {@code null}(Milvus 不传 filter = 全表 ANN)
 *   <li>字符串字段值单引号转义防注入(expr 字符串字面量用单引号)
 *   <li>V9 权限白名单: {@code allowedDocIds=null} = 不加子句(admin/哨兵); 非空集合 → {@code document_id in [...]}
 *   <li>V9 权限哨兵: {@code allowedDocIds} 是空集合 = 禁止召回, 这种情形由 RetrieveService 提前拦截, 本 builder 不会遇到;
 *       但防御性处理为返回一个永真为假的表达式, 保证即便漏过调用也召不回任何 doc。
 * </ul>
 */
public final class MilvusFilterExprBuilder {

    /** 永假表达式, 用于"无可读文档"哨兵场景 (allowedDocIds 已被 RetrieveService 提前拦截, 这里仅作防御)。 */
    static final String ALWAYS_FALSE = "1 == 0";

    private MilvusFilterExprBuilder() {}

    /**
     * 拼接表达式。
     *
     * @param docId 可空; 非空则加 {@code document_id == docId}
     * @param filter 可空; 非空且非 empty 则按字段拼接 source/version/language/tenantId/allowedDocIds
     * @return 拼好的 expr; 无任何过滤条件时返回 null
     */
    public static String build(Long docId, VectorStore.MetadataFilter filter) {
        List<String> clauses = new ArrayList<>(8);
        if (docId != null) {
            clauses.add(MilvusCollectionInitializer.FIELD_DOC_ID + " == " + docId);
        }
        if (filter != null && !filter.isEmpty()) {
            appendStringClause(clauses, MilvusCollectionInitializer.FIELD_SOURCE, filter.source());
            appendStringClause(
                    clauses, MilvusCollectionInitializer.FIELD_VERSION, filter.version());
            appendStringClause(
                    clauses, MilvusCollectionInitializer.FIELD_LANGUAGE, filter.language());
            appendStringClause(
                    clauses, MilvusCollectionInitializer.FIELD_TENANT, filter.tenantId());
            appendDocIdInClause(clauses, filter.allowedDocIds());
        }
        return clauses.isEmpty() ? null : String.join(" and ", clauses);
    }

    private static void appendStringClause(List<String> clauses, String fieldName, String value) {
        if (value == null || value.isBlank()) return;
        clauses.add(fieldName + " == '" + escape(value) + "'");
    }

    /**
     * V9: 把可读 docId 白名单拼为 {@code document_id in [1,2,3]}。
     *
     * <p>约定:
     *
     * <ul>
     *   <li>{@code docIds == null}: 不加子句 (admin 哨兵, 不过滤)
     *   <li>{@code docIds.isEmpty()}: 永假表达式 (无可见文档, 防御性; 正常路径 RetrieveService 已短路)
     *   <li>非空集合: IN 子句
     * </ul>
     */
    private static void appendDocIdInClause(List<String> clauses, Collection<Long> docIds) {
        if (docIds == null) {
            return;
        }
        if (docIds.isEmpty()) {
            clauses.add(ALWAYS_FALSE);
            return;
        }
        StringBuilder sb = new StringBuilder(MilvusCollectionInitializer.FIELD_DOC_ID);
        sb.append(" in [");
        boolean first = true;
        for (Long id : docIds) {
            if (!first) sb.append(",");
            sb.append(id);
            first = false;
        }
        sb.append("]");
        clauses.add(sb.toString());
    }

    /** Milvus expr 字符串字面量单引号转义。 */
    static String escape(String s) {
        return s == null ? "" : s.replace("'", "\\'");
    }
}
