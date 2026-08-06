package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5 / EMS-PR5: Harness 可包装的组件类型。与具体引挢/Tool 解耦, 让通用 Provider 走同一路径。
 *
 * <ul>
 *   <li>{@link #ROUTER} — 适配 PR-3 TaskRouter; 输入 query 输出 RouterDecision
 *   <li>{@link #TOOL} — 适配 PR-4 AgentTool; 经 ToolExecutor 接入
 *   <li>{@link #PLANNER} — 未来 PR-7; 本 PR 不接入 (只占 type)
 *   <li>{@link #SUFFICIENCY_JUDGE} — 未来 PR-7
 *   <li>{@link #ANSWER_COMPOSER} — 未来 PR-8
 *   <li>{@link #CITATION_VERIFIER} — 当前作为 Tool 走 TOOL 路径记录 (EMS-PR5 §18 推荐), 留 type 供未来独立校验链使用
 * </ul>
 */
public enum HarnessComponentType {
    ROUTER,
    TOOL,
    PLANNER,
    SUFFICIENCY_JUDGE,
    ANSWER_COMPOSER,
    CITATION_VERIFIER
}
