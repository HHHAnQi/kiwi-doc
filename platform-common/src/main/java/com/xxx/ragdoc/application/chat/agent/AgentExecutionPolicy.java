package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.Set;

/**
 * PR-6 / EMS-PR6 §4.5: 服务端构造的执行策略。客户端不能扩大 allowlist / increase budget / bypass permission。
 *
 * <p>关键字段:
 *
 * <ul>
 *   <li>{@link #allowedTools} — Step 调用的 Tool 名白名单; {@link
 *       com.xxx.ragdoc.application.chat.agent.AgentToolStep#toolName()} 必须在此集合内, 否则 PlanValidator
 *       fail-closed
 *   <li>{@link #maxEvidence} — {@code EvidenceAccumulator} 保留上限, 防爆炸
 *   <li>{@link #continueOnEmptyResult} — EMPTY_RESULT 是否终止后续 Step (PR-6 比较工作流需 true)
 *   <li>{@link #continueOnRetryableFailure} — PR-6 默认 false (RETRYABLE 不自动重试)
 *   <li>{@link #failOnPermissionDenied} — true (任何 Step PERMISSION_DENIED 转化成 Run 级
 *       REFUSED_PERMISSION)
 * </ul>
 *
 * <p>{@code deadline} 由 Run 全局 {@link AgentBudget#maxExecutionMillis()} 派生（不算 Tool）。
 */
public record AgentExecutionPolicy(
        AgentBudget budget,
        Instant deadline,
        Set<String> allowedTools,
        int maxEvidence,
        int maxEvidenceTokens,
        boolean continueOnEmptyResult,
        boolean continueOnRetryableFailure,
        boolean failOnPermissionDenied) {

    public AgentExecutionPolicy {
        if (budget == null) budget = AgentBudget.pr6Default();
        if (deadline == null) deadline = Instant.now().plusMillis(budget.maxExecutionMillis());
        if (allowedTools == null) allowedTools = Set.of();
        else allowedTools = Set.copyOf(allowedTools);
        if (maxEvidence <= 0) maxEvidence = 20;
        if (maxEvidenceTokens <= 0) maxEvidenceTokens = 4000;
    }

    /**
     * 默认策略用 PR-6 全 Tool 白名单:
     * semantic_search/keyword_search/metadata_search/document_fetch/citation_verify.
     */
    public static AgentExecutionPolicy pr6Default() {
        return new AgentExecutionPolicy(
                AgentBudget.pr6Default(),
                Instant.now().plusMillis(AgentBudget.pr6Default().maxExecutionMillis()),
                Set.of(
                        "semantic_search",
                        "keyword_search",
                        "metadata_search",
                        "document_fetch",
                        "citation_verify"),
                20,
                4000,
                true, // EMPTY_RESULT continue (comparison workflow 需要)
                false, // RETRYABLE 不自动重试
                true);
    }
}
