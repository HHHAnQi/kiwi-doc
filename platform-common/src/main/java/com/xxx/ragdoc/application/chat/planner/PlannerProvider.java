package com.xxx.ragdoc.application.chat.planner;

/**
 * PR-7a / EMS-PR7 §4.5: Planner Provider 接口。
 *
 * <p>实现:
 *
 * <ul>
 *   <li>{@code RuleTemplatePlannerProvider} (platform-bootstrap) — 单测 / 确定性 fixture / model 关闭时
 *   <li>{@code ModelPlannerProvider} (platform-bootstrap) — 调 ChatClient 转 JSON Schema 输出
 *   <li>{@code HarnessAwarePlannerProvider} (platform-bootstrap) — LIVE/RECORD/REPLAY 包装; REPLAY
 *       不调真实 LLM
 * </ul>
 *
 * <p>{@link #plan} <b>不</b>执行任何 Tool / 不持久化 / 不修改状态机; 只返回 {@link PlannerResponse}。
 */
public interface PlannerProvider {

    PlannerResponse plan(PlannerRequest request);
}
