package com.xxx.ragdoc.application.chat.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P0-3 修复: Agentic 执行预算可配化。
 *
 * <p>此前 PlannedAgentPipeline 硬编码 {@code AgentExecutionPolicy.pr6Default()} (maxSteps=3 /
 * maxReplans=<b>0</b>) — Replan 永远 BUDGET_ZERO, "不充分→再检索"链路不可用; 且无任何配置绑定。
 *
 * <p>默认值给 Replan 留足空间: 初始 3 步 + replan 余量; deadline 从 30s 上调到 120s (多跳 + LLM planner + sufficiency
 * 判定的真实 p95 需要)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.agent.budget")
public class AgentBudgetProperties {

    /** 全 run 最大执行步数(初始 plan + replan 追加合计)。 */
    private int maxSteps = 5;

    /** 全 run 最大工具调用次数。 */
    private int maxToolCalls = 10;

    /** 全 run 最大 Planner 调用次数(初始 1 + replan 1 = 2 足够, 留 3)。 */
    private int maxPlannerCalls = 3;

    /** 最大 Replan 次数。与 rag.agent.planner.max-replans 语义一致, 取两者较小值生效。 */
    private int maxReplans = 1;

    /** 执行时长上限(ms), deadline 由此派生。 */
    private long maxExecutionMillis = 120_000L;

    /** 单 run 最大 token(0=不限; token 记账接入后生效, 见 ADR-0012 P2)。 */
    private long maxTotalTokens = 0;

    public AgentBudget toBudget() {
        return new AgentBudget(
                maxSteps,
                maxToolCalls,
                maxPlannerCalls,
                maxReplans,
                maxExecutionMillis,
                0,
                0,
                maxTotalTokens,
                java.math.BigDecimal.ZERO);
    }
}
