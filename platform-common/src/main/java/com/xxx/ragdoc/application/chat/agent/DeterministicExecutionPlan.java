package com.xxx.ragdoc.application.chat.agent;

import java.util.List;

/**
 * PR-6 / EMS-PR6 §4.7: 确定性执行计划。Plan 唯一来源: 服务端代码 / Planner (将来 LLM, 经校验)。
 *
 * <ul>
 *   <li>客户端请求<em>不能</em> 直接提供 Plan (Orchestrator 自己 build)
 *   <li>{@code planId+version} 唯一, 进 Trace
 *   <li>{@link AgentToolStep#stepId()} 在 Plan 内唯一, 由 PlanValidator 检查
 *   <li>本 PR Trigger: 固定 Comparison Workflow (PR-6.Controller 默认 disabled)
 * </ul>
 */
public record DeterministicExecutionPlan(
        String planId, String planVersion, List<AgentToolStep> steps) {

    public DeterministicExecutionPlan {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("planId 必填");
        }
        if (planVersion == null || planVersion.isBlank()) planVersion = "v1";
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps 必填且非空");
        }
        steps = List.copyOf(steps);
    }
}
