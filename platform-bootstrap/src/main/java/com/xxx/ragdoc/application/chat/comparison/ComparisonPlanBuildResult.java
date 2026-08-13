package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;

/**
 * PR-6c / EMS-PR6c §5.1: ComparisonPlanFactory 输出。
 *
 * <p>包含:
 *
 * <ul>
 *   <li>{@link #plan} — 稳定拓扑序的两个 required Step (compare-left / compare-right)
 *   <li>{@link #policy} — 服务端构造的 {@link AgentExecutionPolicy} (客户端不能修改预算)
 *   <li>{@link #leftTarget} / {@link #rightTarget} — 两侧比较对象 (用于 Prompt / Trace / partition)
 *   <li>{@link #leftToolChoice} / {@link #rightToolChoice} — Tool 选择, 用于 trace 与 Citation 校验
 * </ul>
 *
 * <p>{@code valid=false} 时只有 {@code invalidReason} 有意义, 其余字段为 null/默认。
 */
public record ComparisonPlanBuildResult(
        boolean valid,
        String invalidReason,
        DeterministicExecutionPlan plan,
        AgentExecutionPolicy policy,
        ComparisonTarget leftTarget,
        ComparisonTarget rightTarget,
        ComparisonToolChoice leftToolChoice,
        ComparisonToolChoice rightToolChoice) {

    public static ComparisonPlanBuildResult invalid(String reason) {
        return new ComparisonPlanBuildResult(false, reason, null, null, null, null, null, null);
    }

    public static ComparisonPlanBuildResult ok(
            DeterministicExecutionPlan plan,
            AgentExecutionPolicy policy,
            ComparisonTarget left,
            ComparisonTarget right,
            ComparisonToolChoice leftTool,
            ComparisonToolChoice rightTool) {
        return new ComparisonPlanBuildResult(
                true, null, plan, policy, left, right, leftTool, rightTool);
    }
}
