package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: semantic_search / keyword_search / metadata_search 三个检索 Tool 的共用 input。
 *
 * <p>字段:
 *
 * <ul>
 *   <li>{@link #query} 非空 1-500 字 (与既有 ChatCommand 同规则)
 *   <li>{@link #topK} ∈ [1, 20]; 服务端上限 100 (ToolDescriptor.maxResults)
 *   <li>{@link #filters} 可选 metadata 过滤 (source/version/language); 不含 tenantId (由 Tool 注入)
 * </ul>
 *
 * <p><b>禁止</b> 携带 tenantId / userId / roles / adminOverride 字段 (ToolExecutor 会扫描 toString 并拒)。
 */
public record SearchInput(String query, Integer topK, SearchFilters filters) implements ToolInput {

    public SearchInput {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("SearchInput.query 不能为空");
        }
        if (query.length() > 500) {
            throw new IllegalArgumentException("SearchInput.query 长度不能超过 500");
        }
        if (topK != null && (topK < 1 || topK > 20)) {
            throw new IllegalArgumentException("SearchInput.topK 必须在 [1, 20]");
        }
        filters = filters == null ? SearchFilters.empty() : filters;
    }

    /** Convenience: 不带 filter 的构造。 */
    public SearchInput(String query, Integer topK) {
        this(query, topK, null);
    }

    /**
     * 规范化为 dedup key 一部分; query trim + lowercase + 字典序拼接 filters。
     *
     * <p>key 要点: 同样语义但不同字段顺序的 filters 产生相同 normalized 字符串。
     */
    @Override
    public String normalizedForDedup() {
        StringBuilder sb = new StringBuilder();
        sb.append("q=").append(query.trim().toLowerCase());
        sb.append("|k=").append(topK == null ? 5 : topK);
        // filters 顺序无关: 按 source/version/language 字段顺序固定输出
        sb.append("|f=").append(filters.normalizedString());
        return sb.toString();
    }

    /** 检索 Tool 的 metadata 过滤; 不含 tenantId / docIds (ACL 由 Tool 内部注入)。 */
    public record SearchFilters(String source, String version, String language) {
        public SearchFilters {
            source = blankToNull(source);
            version = blankToNull(version);
            language = blankToNull(language);
        }

        public static SearchFilters empty() {
            return new SearchFilters(null, null, null);
        }

        /** 字典序固定顺序输出, 让 dedup 不被字段顺序打乱。 */
        public String normalizedString() {
            return "language="
                    + (language == null ? "" : language.toLowerCase())
                    + "|source="
                    + (source == null ? "" : source.toLowerCase())
                    + "|version="
                    + (version == null ? "" : version.toLowerCase());
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }
    }
}
