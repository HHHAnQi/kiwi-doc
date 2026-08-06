package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;

/**
 * PR-6a.2: 预算预留 (与 {@link AgentUsage} 分离)。
 *
 * <p>语义:
 *
 * <ul>
 *   <li>{@link AgentBudget} = 最大允许量 (不可变, 服务端设置)
 *   <li>{@link AgentBudgetReservation} = 已预留、尚未结算的量 (PR-6b BudgetManager 在 Tool 执行前预留)
 *   <li>{@link AgentUsage} = 已真实发生、已结算的量 (Tool 执行完成后结算)
 * </ul>
 *
 * <p>并发安全不靠 record 自身: PR-6b BudgetManager 用 CAS UPDATE agent_run
 * (version + status 双条件) 保证只有一个 writer 成功 reserve。
 */
public record AgentBudgetReservation(
        int reservedSteps,
        int reservedToolCalls,
        int reservedPlannerCalls,
        long reservedInputTokens,
        long reservedOutputTokens,
        BigDecimal reservedEstimatedCost) {

    public AgentBudgetReservation {
        if (reservedSteps < 0) reservedSteps = 0;
        if (reservedToolCalls < 0) reservedToolCalls = 0;
        if (reservedPlannerCalls < 0) reservedPlannerCalls = 0;
        if (reservedInputTokens < 0) reservedInputTokens = 0;
        if (reservedOutputTokens < 0) reservedOutputTokens = 0;
        reservedEstimatedCost = reservedEstimatedCost == null ? BigDecimal.ZERO : reservedEstimatedCost;
    }

    public static AgentBudgetReservation zero() {
        return new AgentBudgetReservation(0, 0, 0, 0, 0, BigDecimal.ZERO);
    }
}
