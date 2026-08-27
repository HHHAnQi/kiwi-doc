package com.xxx.ragdoc.application.metrics;

/**
 * 指标上报端口 (Hexagonal Port)。
 *
 * <p>本接口属 application 层, 实现 {@code RagdocMetrics} 在 infrastructure/metrics 层。application 层的所有
 * service 仅依赖本接口, 让 ArchUnit "application 不依赖 infrastructure" 规则不破。
 *
 * <p>方法语义约定见 {@code com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics} 的 Javadoc。实现侧 负责
 * Micrometer API 调用 + 单位 / 标签规范化。本接口仅为依赖倒置工具, 不复制实现文档。
 *
 * <h3>调用方分布</h3>
 *
 * <ul>
 *   <li>ChatService: chat/chatStream 的 latency / first_token / token counter / rewrite / shift /
 *       compression
 *   <li>RetrieveService: retrieve latency + recall + rerank latency
 *   <li>Infrastructure conversation 内部 (QueryContextualizer / TopicShiftDetector /
 *       HistoryCompressor / PromptAssembler) 也用, 这些不属 application 层, 直接拿
 *       infrastructure.RagdocMetrics 无违规。
 * </ul>
 */
public interface MetricsPort {
    void recordChatTotal(long durationMs, String outcome);

    void recordChatFirstToken(long latencyMs);

    void incrementLlmCall(String route);

    void recordRetrieveRecall(int count);

    void recordRerankLatency(long durationMs, boolean success);

    void recordRetrieveTotal(long durationMs);

    /** Milvus 命中但 MySQL Chunk/Document 不存在或不再可检索的陈旧索引数量。 */
    default void recordRetrieveStaleHit(int count) {
        // default no-op
    }

    /** 派生索引返回了越出租户/文档白名单的候选；服务层已拒绝这些候选。 */
    default void recordRetrieveSecurityRejectedHit(int count) {
        // default no-op
    }

    void recordRewriteLatency(long durationMs, String outcome);

    void incrementTopicShift(String detected);

    void incrementCompression(String outcome);

    void incrementHistoryForceTruncate();

    void recordTokens(int promptTokens, int completionTokens, String route, String model);

    /**
     * PR-4 / EMS-PR4: Tool 调用计数 + 状态 + 延迟。一次 Tool 执行记一笔 (含 dedup_hit 也记一笔)。
     *
     * @param toolName Tool 名 (semantic_search / document_fetch / ...)
     * @param status ToolStatus 枚举名 (SUCCESS / EMPTY_RESULT / TIMEOUT / PERMISSION_DENIED ...)
     * @param latencyMs 调用耗时; dedup_hit 时携带原 result 的 latency
     */
    default void recordToolCall(String toolName, String status, long latencyMs) {
        // default no-op, 让 RagdocMetrics impl override 即可接入 Micrometer
    }

    /** PR-4: Tool 产出的 Evidence 数量, 用于 evidence_yield by tool_name 监控。 */
    default void recordToolEvidenceYield(String toolName, int count) {
        // default no-op
    }

    /** PR-4: 命中调用去重 cache 计数; 用于 dedup 有效性监控。 */
    default void incrementToolDedupHit(String toolName) {
        // default no-op
    }

    /**
     * P0-1(降级链): Planner 降级计数。stage 取值:
     * model_retry_success / rule_fallback / classic_fallback。
     * 非零速率即说明 Model Planner 依赖的 LLM 在劣化 — 告警语义, 不是错误率。
     */
    default void incrementPlannerDegradation(String stage) {
        // default no-op
    }
}
