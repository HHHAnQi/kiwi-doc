package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Task 6: Query Enhancement 配置 (单轮 rewrite + expansion)。
 *
 * <p>控制 {@link com.xxx.ragdoc.application.chat.conversation.port.QueryProcessorPort} 实现是否启用、走哪条路。
 *
 * <p>沿用 {@code RerankProperties} 模式 (enabled 主开关 + mode 子配置) — 默认全部关闭,
 * 改 env {@code RAG_QUERY_ENHANCE_ENABLED=true} 开启。
 *
 * <p>启用前必备: fallback LLM route 已配 (走 LlmRouter 便宜 route, DeepSeek-v3 等)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.query-enhance")
public class QueryEnhanceProperties {

    /** 主开关, 默认 false。任务要求: enable_query_rewrite=false 默认关。 */
    private boolean enabled = false;

    /** 增强模式: rewrite (只重写) / expansion (只多元) / both (重写 + 多元)。默认 rewrite。 */
    private Mode mode = Mode.REWRITE;

    /** expansion 模式下, 多元查询的最大数量 (含主 rewrite 句)。默认 3。 */
    private int maxExpansionQueries = 3;

    /** LLM 超时, 毫秒。默认 5s — enhance 失败不挂主流程。 */
    private int timeoutMs = 5000;

    public enum Mode {
        REWRITE,
        EXPANSION,
        BOTH,
    }
}
