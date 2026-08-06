package com.xxx.ragdoc.application.chat.router;

/**
 * PR-3 / EMS-PR3: 任务路由器接口。
 *
 * <p>实现方 (PR-3.2 {@code RuleBasedTaskRouter}, 后续 PR May Add LLM Router) 接受用户原始 query,
 * 返回一次性的 {@link RouterDecision}。<b>不允许</b> 二次决策或 Replan (后者由 PR-7 Planner 负责)。
 *
 * <p>Router 不验证 ACL / tenantId, 只做意图分类与策略选择; 策略落地的 ACL 守门由 Pipeline 决定。
 */
public interface TaskRouter {
    RouterDecision route(String query);
}
