package com.xxx.ragdoc.infrastructure.metrics;

import com.xxx.ragdoc.application.metrics.MetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 3.A: RAG 核心 SLO 指标统一暴露口。
 *
 * <p>所有 5 项 SLO 集中在本类, 避免散落在 service / controller 多处直接拿 {@link MeterRegistry} 导致 tag / unit /
 * 命名漂移。chat / retrieve / rerank 路径各自调对应方法, 不感知 Micrometer API。
 *
 * <h3>5 项 SLO 指标</h3>
 *
 * <ol>
 *   <li>{@code ragdoc.chat.first_token_latency} (Timer, ms): SSE 首 token 延迟, ADR-0004 L1 ≤ 2s p95
 *   <li>{@code ragdoc.chat.total_latency} (Timer, ms): chat/chatStream 端到端, ADR-0004 L1 ≤ 15s p95
 *   <li>{@code ragdoc.retrieve.recall_count} (Counter / DistributionSummary): 每次 retrieve 的 finalN
 *   <li>{@code ragdoc.rerank.latency} (Timer, ms): rerank 客户端耗时(仅 rerank 路径)
 *   <li>{@code ragdoc.llm.call_total} (Counter): LLM 调用次数 + tag {@code
 *       outcome=ok|degraded|fallback}
 * </ol>
 *
 * <p>所有 timer 直方图 + percentile 在 {@code application.yml} {@code management.metrics.distribution}
 * 配置。
 *
 * <p>架构师备注:
 *
 * <ul>
 *   <li>不带 serviceName tag: 单 service 部署, 多余 tag 徒增基数。Prometheus relabel 在抓取侧加 job。
 *   <li>DistributionSummary 不预声明 → 用 MeterRegistry 自动建, helper 直接 .record(value)。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RagdocMetrics implements MetricsPort {

    private final MeterRegistry registry;

    /** chat/chatStream 全程 — ok 路径(收到完整答案)。 */
    public void recordChatTotal(long durationMs, String outcome) {
        registry.timer("ragdoc.chat.total_latency", "outcome", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if ("ok".equals(outcome)) {
            // 成功路径同时上调 llm.call_total{outcome=ok}
            registry.counter("ragdoc.llm.call_total", "outcome", "ok").increment();
        } else {
            registry.counter("ragdoc.llm.call_total", "outcome", outcome).increment();
        }
    }

    /** SSE 首 token 延迟(ms)。非 SSE chat 不调此方法。 */
    public void recordChatFirstToken(long latencyMs) {
        registry.timer("ragdoc.chat.first_token_latency").record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /** LLM 调用 count(tag 区分 primary/fallback)。供 LlmRouter / OpenAiCompatibleLlmClient 上调。 */
    public void incrementLlmCall(String route) {
        registry.counter("ragdoc.llm.call_total", "route", route, "outcome", "attempted")
                .increment();
    }

    /** retrieve 召回的最终命中条数(=喂 LLM 的 context chunk 数)。 */
    public void recordRetrieveRecall(int count) {
        registry.summary("ragdoc.retrieve.recall_count").record(count);
    }

    /** rerank 调用耗时(ms)。rerank disabled 不调。 */
    public void recordRerankLatency(long durationMs, boolean success) {
        registry.timer("ragdoc.rerank.latency", "outcome", success ? "ok" : "failed")
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** retrieve 端到端(ms, 含 embed + milvus search + 可选 rerank)。 */
    public void recordRetrieveTotal(long durationMs) {
        registry.timer("ragdoc.retrieve.total_latency").record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRetrieveStaleHit(int count) {
        if (count > 0) {
            registry.counter("ragdoc.retrieve.stale_hit_total").increment(count);
        }
    }

    @Override
    public void recordRetrieveSecurityRejectedHit(int count) {
        if (count > 0) {
            registry.counter("ragdoc.retrieve.security_rejected_hit_total").increment(count);
        }
    }

    // ───── Phase 1 / C2 (ADR-0011 §11): conversation / memory SLO ─────

    /**
     * Rewrite LLM (condense question) 调用 latency。
     *
     * @param outcome skip=history 空直接跳过; ok=LLM 成功; failed=LLM 失败回退原 query
     */
    public void recordRewriteLatency(long durationMs, String outcome) {
        registry.timer("ragdoc.conversation.rewrite_latency", "outcome", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** Topic shift 检测结果 (similarity < threshold 即 true)。 */
    public void incrementTopicShift(String detected) {
        registry.counter("ragdoc.conversation.topic_shift_total", "detected", detected).increment();
    }

    /**
     * 压缩结果 count.
     *
     * @param outcome ok=压缩成功; failed=LLM/Redis 异常; invalid=quality gate 拒绝(摘要太短); skipped=debounce
     *     / size 不足
     */
    public void incrementCompression(String outcome) {
        registry.counter("ragdoc.conversation.compression_total", "outcome", outcome).increment();
    }

    /**
     * 历史硬 cut 次数 (ADR-0011 §8.4 极端兜底: buffer > 5 时 PromptAssembler 砍)。
     *
     * <p>Grafana 报警: 增长率 > 0 持续 5min → oncall 介入(压缩链异常)。
     */
    public void incrementHistoryForceTruncate() {
        registry.counter("ragdoc.conversation.force_truncate_total").increment();
    }

    // ───── Phase 3 / P3-5: cost observability ─────

    /**
     * Phase 3 / P3-5: LLM 调用 token 使用量 counter。
     *
     * <p>Grafana 通过 PromQL 按 model 单价换算近似成本 (USD/天 / 周累计):
     *
     * <pre>
     *   # 例: 7d 总成本估算
     *   sum_over_time(ragdoc_llm_token_total{type="prompt"}[7d]) * $GLM_PRICE_PER_K_PROMPT / 1000
     *   + sum_over_time(ragdoc_llm_token_total{type="completion"}[7d]) * $GLM_PRICE_PER_K_COMPLETION / 1000
     * </pre>
     *
     * <p>tags:
     *
     * <ul>
     *   <li>{@code type=prompt|completion}: 区分提示词与生成消耗 (completion 通常贵 2-4x)
     *   <li>{@code route}: 路由名 (llm-primary / llm-fallback / rewrite-llm …), 区分降级 vs 主链路成本
     *   <li>{@code model}: 模型名 (glm-4-plus / deepseek-chat / qwen-max), 单价各异
     * </ul>
     *
     * @param promptTokens 系统提示 + context + 用户问题 消耗 token
     * @param completionTokens LLM 生成答案消耗 token
     * @param route 路由名 (ChatService 传入, 区分 primary/fallback/rewrite)
     * @param model 实际请求的 model 字段 (路由配置内的 route.getModel())
     */
    public void recordTokens(int promptTokens, int completionTokens, String route, String model) {
        if (promptTokens > 0) {
            registry.counter(
                            "ragdoc.llm.token_total",
                            "type",
                            "prompt",
                            "route",
                            route == null ? "unknown" : route,
                            "model",
                            model == null ? "unknown" : model)
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter(
                            "ragdoc.llm.token_total",
                            "type",
                            "completion",
                            "route",
                            route == null ? "unknown" : route,
                            "model",
                            model == null ? "unknown" : model)
                    .increment(completionTokens);
        }
    }

    // 注: 当前活跃 conversation 数 (gauge) C2 不实现。Micrometer Gauge 要求 supplier / 弱引用,
    //     传 long value 每次重新注册会拿不到更新值, 别扭。
    //     C8 配 Grafana 时再做 — 届时 RedisConversationStore 维护 AtomicLong 引用, Gauge 跟踪它。

    // ─── P0修复(短板9): Agent 域指标 ──────────────────────────

    public void recordAgentSufficiency(String outcome) {
        registry.counter("ragdoc.agent.sufficiency_total", "outcome", outcome).increment();
    }

    public void recordAgentReplan(String outcome) {
        registry.counter("ragdoc.agent.replan_total", "outcome", outcome).increment();
    }

    public void recordAgentBudgetDenied(String dimension) {
        registry.counter("ragdoc.agent.budget_denied_total", "dimension", dimension).increment();
    }

    public void recordAgentE2ELatency(long durationMs) {
        registry.timer("ragdoc.agent.e2e_duration").record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordAgentLlmCall(String component) {
        registry.counter("ragdoc.agent.llm_calls_total", "component", component).increment();
    }
}
