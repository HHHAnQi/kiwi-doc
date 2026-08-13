package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;

/**
 * PR-6 / EMS-PR6 §4.4: Agent Run 当前用量。BudgetManager 在每 Step 后结 update。
 *
 * <ul>
 *   <li>{@link #usedSteps} — 已执行的 Step 数 (含 dedup/replay)
 *   <li>{@link #usedToolCalls} — 真实 Tool 调用次数 (REPLAY 不计入, dedup 重复命中不计)
 *   <li>{@code replays} — 不在 PR-6 字段; dedup/replay 由 Trace 标识, 预算只看真实 calls
 *   <li>{@link #elapsedMillis} — 用作 budget total deadline 校验
 * </ul>
 */
public record AgentUsage(
        int usedSteps,
        int usedToolCalls,
        int usedPlannerCalls,
        long usedInputTokens,
        long usedOutputTokens,
        long usedTotalTokens,
        long elapsedMillis,
        BigDecimal estimatedCost) {

    public static AgentUsage zero() {
        return new AgentUsage(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO);
    }

    public AgentUsage incStep() {
        return new AgentUsage(
                usedSteps + 1,
                usedToolCalls,
                usedPlannerCalls,
                usedInputTokens,
                usedOutputTokens,
                usedTotalTokens,
                elapsedMillis,
                estimatedCost);
    }

    public AgentUsage incRealToolCall() {
        return new AgentUsage(
                usedSteps,
                usedToolCalls + 1,
                usedPlannerCalls,
                usedInputTokens,
                usedOutputTokens,
                usedTotalTokens,
                elapsedMillis,
                estimatedCost);
    }

    public AgentUsage withElapsed(long elapsed) {
        return new AgentUsage(
                usedSteps,
                usedToolCalls,
                usedPlannerCalls,
                usedInputTokens,
                usedOutputTokens,
                usedTotalTokens,
                elapsed,
                estimatedCost);
    }
}
