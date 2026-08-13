package com.xxx.ragdoc.application.chat.router;

/**
 * PR-3 / EMS-PR3: Router 选定的执行策略。是 PipelineType 的子集表达, 但保留 REFUSE 语义 (PipelineType 中没有"拒绝"类型 — 拒绝由
 * Orchestrator 在 dispatch 前提前处理, 不进入任何 Pipeline)。
 *
 * <ul>
 *   <li>{@link #CLASSIC_RAG} — 走既有 Classic RAG (Dense 或 Hybrid 由 retrieve mode 决定)
 *   <li>{@link #TARGETED_RAG} — keyword + metadata + version filter 精确召回 (PR-3.3 接入)
 *   <li>{@link #FIXED_WORKFLOW} — 比较工作流 / 证据补全工作流 (PR-3.4 接入)
 *   <li>{@link #PLANNED_AGENT} (PR-7c) — MULTI_HOP Planner Pipeline; 需 Feature Flag 全部开启
 *   <li>{@link #REFUSE} — 拒绝回答 (UNANSWERABLE 路径, Orchestrator 直接产拒答 ChatResult)
 * </ul>
 *
 * <p>PR-3.2 Router 第一版只产出 {@link #CLASSIC_RAG} / {@link #REFUSE} (TARGETED / WORKFLOW Pipeline 暂不
 * 接入); 已带好枚举值便于 PR-3.3/3.4 扩展。
 */
public enum ExecutionStrategy {
    DIRECT_CHAT,
    CLASSIC_RAG,
    TARGETED_RAG,
    FIXED_WORKFLOW,
    PLANNED_AGENT,
    TOOL_EXECUTION,
    REFUSE
}
