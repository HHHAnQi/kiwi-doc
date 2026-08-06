package com.xxx.ragdoc.domain.shared;

/**
 * PR-2 / EMS-PR2: 可执行 pipeline 类型。Orchestrator registry 按 type 索引 ChatPipeline Bean。
 *
 * <p>PR-2 中只有 {@link #CLASSIC_RAG} 真实存在; 其它 type 在后续 PR 才实现。Registry miss →
 * 失败关闭 (HTTP 500), 不得自动 fallback 到任意 pipeline。
 */
public enum PipelineType {
    /** 现有 Classic RAG 链路: 检索 → Rerank → Context → LLM → 引用核验。 */
    CLASSIC_RAG,
    /** 目标 RAG (keyword + metadata + 版本/时间过滤); 计划 PR-3 实现。 */
    TARGETED_RAG,
    /** 固定确定性工作流 (比较 / 证据补全); 计划 PR-3 实现。 */
    FIXED_WORKFLOW,
    /** PR-7c 受控 Planner Agent RAG (MULTI_HOP 单次 Plan + 最多一次 Replan)。 */
    PLANNED_AGENT,
    /** 受控 Planner Agent RAG; 计划 PR-6/PR-7 实现, 仅供参考。 */
    AGENTIC_RAG
}
