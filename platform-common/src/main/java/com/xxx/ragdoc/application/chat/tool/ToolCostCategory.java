package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Tool 成本分级。Executor / Sufficiency 可据此预估 budget; 第一版只做粗分级, 算不上 严格 token / 美元成本,
 * 但能让上层在 "再调一次 LLM-verify" 与 "再调一次 dense retrieval" 间做权衡。
 */
public enum ToolCostCategory {
    /** 纯 Milvus / MySQL 读, 不调 LLM, 不调 Embedding。最强廉价。 */
    INDEX_READ,
    /** 含 Embedding 调用 (BGE) 但不含 LLM。中等。 */
    EMBEDDING,
    /** 含 LLM 调用 (citation_verify)。最贵, 严格 FPS / 成本控制。 */
    LLM,
    /** 未明确归类; 默认按 INDEX_READ 处理。 */
    UNKNOWN
}
