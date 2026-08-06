package com.xxx.ragdoc.application.chat.planner;

import java.util.List;
import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2: Planner/Sufficiency 用的"剩余预算"视图 (Revision §5.2 数据最小化)。
 *
 * <p>不含 token / cost / 时间细节的真实 Principal / API key; 只暴露 Planner 可消费的 count 型字段。
 */
public record AgentBudgetView(
        int remainingSteps,
        int remainingToolCalls,
        int maxSteps,
        int maxToolCalls,
        long remainingExecutionMillis,
        int remainingReplans) {

    public AgentBudgetView {
        if (remainingSteps < 0) remainingSteps = 0;
        if (remainingToolCalls < 0) remainingToolCalls = 0;
        if (maxSteps < 0) maxSteps = 0;
        if (maxToolCalls < 0) maxToolCalls = 0;
        if (remainingExecutionMillis < 0) remainingExecutionMillis = 0;
        if (remainingReplans < 0) remainingReplans = 0;
    }
}
