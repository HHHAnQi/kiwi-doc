package com.xxx.ragdoc.application.chat.conversation;

/**
 * QueryContextualizer 返回值, ADR-0011 §4。
 *
 * <p>三类 outcome:
 *
 * <ul>
 *   <li>{@code skip}: history 空 / 鹦鹉学舌 (rewrite == 原 query), 用原 query 走
 *   <li>{@code ok}: LLM 成功 rewrite, 用 rewrittenQuery 走 retrieve
 *   <li>{@code failed}: LLM/网络异常, fallback 用原 query 走 (用户不感知)
 * </ul>
 *
 * <p>调用方根据 {@link #outcome} 和 {@link #retrieveQuery} 决策 retrieve 路径, 不感知 rewrite
 * 内部失败 / 成功细节。metrics 上调由 {@code QueryContextualizer} 内部完成, 调用方无需重复。
 *
 * @author Phase 1 / C3 (ADR-0011)
 */
public record ContextualizeResult(
        String originalQuery, String rewrittenQuery, String outcome, long durationMs) {

    /** 启用条件不满足 (history 空等), 跳过 rewrite。 */
    public static ContextualizeResult skipped(String originalQuery, long durationMs) {
        return new ContextualizeResult(originalQuery, originalQuery, "skip", durationMs);
    }

    /** LLM rewrite 成功。 */
    public static ContextualizeResult success(
            String originalQuery, String rewrittenQuery, long durationMs) {
        return new ContextualizeResult(originalQuery, rewrittenQuery, "ok", durationMs);
    }

    /** LLM 失败, fallback 用原 query。 */
    public static ContextualizeResult failed(String originalQuery, long durationMs) {
        return new ContextualizeResult(originalQuery, originalQuery, "failed", durationMs);
    }

    /** rewrite 后实际该走的 retrieve query。 */
    public String retrieveQuery() {
        return rewrittenQuery;
    }
}
