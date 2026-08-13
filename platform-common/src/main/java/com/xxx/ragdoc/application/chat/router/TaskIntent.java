package com.xxx.ragdoc.application.chat.router;

/**
 * PR-3 / EMS-PR3: 任务意图分类。第一版仅支持以下 7 类 (任务文档 §4.3), 不得增加没有评测数据的 Intent。
 *
 * <p>映射 (PR-3 §3 锁定):
 *
 * <ul>
 *   <li>{@link #FACT} → CLASSIC_RAG
 *   <li>{@link #ENTITY_LOOKUP} → TARGETED_RAG
 *   <li>{@link #NUMERIC_OR_VERSION} → TARGETED_RAG
 *   <li>{@link #COMPARISON} → FIXED_WORKFLOW
 *   <li>{@link #MULTI_HOP} → FIXED_WORKFLOW
 *   <li>{@link #SUMMARY} → CLASSIC_RAG
 *   <li>{@link #UNANSWERABLE} → REFUSE
 * </ul>
 *
 * <p>该枚举仅描述意图, 不绑定 ExecutionStrategy — 后者由 {@link RouterDecision#strategy()} 表达, 让"低置信度回退 Hybrid
 * RAG"等场景可保留 intent 不变但 strategy 回退为 CLASSIC_RAG。
 */
public enum TaskIntent {
    CHAT,
    TOOL,
    FACT,
    ENTITY_LOOKUP,
    NUMERIC_OR_VERSION,
    COMPARISON,
    MULTI_HOP,
    SUMMARY,
    UNANSWERABLE
}
