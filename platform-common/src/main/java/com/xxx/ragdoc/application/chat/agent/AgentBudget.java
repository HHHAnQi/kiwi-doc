package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;

/**
 * PR-6 / EMS-PR6 §4.3: 服务端生成的 Agent 预算。客户端请求体 <b>不能</b> 扩大预算。
 *
 * <p>PR-6 配置: maxPlannerCalls=0, maxReplans=0 (Planner 不存在哪都不接)
 *
 * <p>默认值由 {@code AgentBudgetDefaults} 提供 (config-driven); 这里只持有运行时不可变值。
 */
public record AgentBudget(
        int maxSteps,
        int maxToolCalls,
        int maxPlannerCalls,
        int maxReplans,
        long maxExecutionMillis,
        long maxInputTokens,
        long maxOutputTokens,
        long maxTotalTokens,
        BigDecimal maxEstimatedCost) {

    public AgentBudget {
        if (maxSteps < 0) throw new IllegalArgumentException("maxSteps >= 0");
        if (maxToolCalls < 0) throw new IllegalArgumentException("maxToolCalls >= 0");
        if (maxPlannerCalls < 0) maxPlannerCalls = 0;
        if (maxReplans < 0) maxReplans = 0;
        if (maxExecutionMillis <= 0) maxExecutionMillis = 30_000L;
        if (maxInputTokens < 0) maxInputTokens = 0;
        if (maxOutputTokens < 0) maxOutputTokens = 0;
        if (maxTotalTokens < 0) maxTotalTokens = 0;
        maxEstimatedCost = maxEstimatedCost == null ? BigDecimal.ZERO : maxEstimatedCost;
    }

    /** PR-6 PRD: 保守默认 (max-steps 3, max-tool-calls 5, planner 0 / replan 0)。 */
    public static AgentBudget pr6Default() {
        return new AgentBudget(3, 5, 0, 0, 30_000L, 0, 0, 0, BigDecimal.ZERO);
    }
}
