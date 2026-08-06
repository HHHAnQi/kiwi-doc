package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6b / EMS-PR6 §4.3: 预算维度。BudgetManager 在联合判断 (usage + reservation + 本次申请) 时
 * 任一维度突破即返回对应 {@link BudgetDimension}, Executor 据此把 Run 推到 BUDGET_EXCEEDED +
 * 当前 Step 转 SKIPPED_BUDGET。
 */
public enum BudgetDimension {
    MAX_STEPS,
    MAX_TOOL_CALLS,
    DEADLINE,
    INPUT_TOKENS,
    OUTPUT_TOKENS,
    TOTAL_TOKENS,
    COST
}
