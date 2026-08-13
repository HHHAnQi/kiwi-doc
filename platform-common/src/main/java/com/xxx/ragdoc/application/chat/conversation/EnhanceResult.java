package com.xxx.ragdoc.application.chat.conversation;

import java.util.List;

/**
 * Task 6: QueryProcessor 返回值 — 单轮 query rewrite + expansion 的统一结果。
 *
 * <p>三类 outcome (与 {@link ContextualizeResult} 同风格, 便于上层 trace 共用 observation pattern):
 *
 * <ul>
 *   <li>{@code SKIP}: parrot-echo (rewrite == original) / LLM route 缺失 → 用原 query 走
 *   <li>{@code OK}: LLM 成功增强, 用 rewrittenQuery 走 retrieve (expansion 时同时返回 expansions)
 *   <li>{@code FAILED}: LLM/网络异常 / 熔断 / JSON 解析错, fallback 用原 query (用户无感)
 * </ul>
 *
 * <p>{@link #primaryQuery()} 是 retrieve 主链喂给 embed 的 query; {@link #allQueries()} 是 expansion
 * 路径给出的多查询集合 (未来向量并行召回用, 当前 Task 仅用主链)。
 */
public record EnhanceResult(
        String originalQuery,
        String rewrittenQuery,
        List<String> expandedQueries,
        String outcome,
        String errorMessage,
        long durationMs) {

    public EnhanceResult {
        if (rewrittenQuery == null) rewrittenQuery = originalQuery;
        if (expandedQueries == null) expandedQueries = List.of();
    }

    /** 启用条件不满足 / parrot-echo → 不增强, 等价原 query 走。 */
    public static EnhanceResult skipped(String originalQuery, long durationMs) {
        return new EnhanceResult(originalQuery, originalQuery, List.of(), "skip", null, durationMs);
    }

    /** LLM 增强成功 (可能 rewrite + expansion 都有)。 */
    public static EnhanceResult success(
            String originalQuery,
            String rewrittenQuery,
            List<String> expandedQueries,
            long durationMs) {
        return new EnhanceResult(
                originalQuery,
                rewrittenQuery,
                expandedQueries == null ? List.of() : expandedQueries,
                "ok",
                null,
                durationMs);
    }

    /** LLM/网络/熔断/JSON 解析失败, fallback 用原 query。 */
    public static EnhanceResult failed(String originalQuery, String errorMessage, long durationMs) {
        return new EnhanceResult(
                originalQuery, originalQuery, List.of(), "failed", errorMessage, durationMs);
    }

    /** 主 retrieve query (rewrite 优先, 否则原 query)。 */
    public String primaryQuery() {
        return rewrittenQuery;
    }

    /** 全部 query：仅改写时使用主 Query；存在 Expansion 时保留原始 Query 并追加扩展 Query。 */
    public List<String> allQueries() {
        if (expandedQueries.isEmpty()) {
            return List.of(primaryQuery());
        }
        // rewrite 主链放首位, 再跟 expansions (去重)
        var out = new java.util.LinkedHashSet<String>();
        out.add(primaryQuery());
        out.add(originalQuery);
        out.addAll(expandedQueries);
        out.removeIf(query -> query == null || query.isBlank());
        return List.copyOf(out);
    }
}
