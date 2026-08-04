package com.xxx.ragdoc.application.metrics;

/**
 * 指标上报端口 (Hexagonal Port)。
 *
 * <p>本接口属 application 层, 实现 {@code RagdocMetrics} 在 infrastructure/metrics 层。application
 * 层的所有 service 仅依赖本接口, 让 ArchUnit "application 不依赖 infrastructure" 规则不破。
 *
 * <p>方法语义约定见 {@code com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics} 的 Javadoc。实现侧
 * 负责 Micrometer API 调用 + 单位 / 标签规范化。本接口仅为依赖倒置工具, 不复制实现文档。
 *
 * <h3>调用方分布</h3>
 *
 * <ul>
 *   <li>ChatService: chat/chatStream 的 latency / first_token / token counter / rewrite / shift / compression
 *   <li>RetrieveService: retrieve latency + recall + rerank latency
 *   <li>Infrastructure conversation 内部 (QueryContextualizer / TopicShiftDetector / HistoryCompressor /
 *       PromptAssembler) 也用, 这些不属 application 层, 直接拿 infrastructure.RagdocMetrics 无违规。
 * </ul>
 */
public interface MetricsPort {
    void recordChatTotal(long durationMs, String outcome);

    void recordChatFirstToken(long latencyMs);

    void incrementLlmCall(String route);

    void recordRetrieveRecall(int count);

    void recordRerankLatency(long durationMs, boolean success);

    void recordRetrieveTotal(long durationMs);

    void recordRewriteLatency(long durationMs, String outcome);

    void incrementTopicShift(String detected);

    void incrementCompression(String outcome);

    void incrementHistoryForceTruncate();

    void recordTokens(int promptTokens, int completionTokens, String route, String model);
}
