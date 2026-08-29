package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多轮对话配置 (绑定 application.yml 里 {@code rag.conversation.*})，ADR-0011 §Feature Flag 矩阵。
 *
 * <pre>
 * rag:
 *   conversation:
 *     enabled: false           # master switch
 *     compress: false          # 异步压缩
 *     compress-threshold: 6
 *     topic-shift-detect: false
 *     topic-shift-threshold: 0.5
 *     max-recent-turns: 3
 *     ttl-hours: 24
 * </pre>
 *
 * <p>架构债清理: 本类从 infrastructure.conversation 包移到 application.chat (与 ChatMessages 同包), 让 ChatService
 * 不再依赖 infrastructure 层。配置 @ConditionalOnProperty / bean scan 不受影响。
 *
 * @author Phase 1 / C2 (ADR-0011)
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.conversation")
public class ConversationProperties {

    /** master switch. false 时 NoOpConversationStore 接管 C1。 */
    private boolean enabled = false;

    /** 异步压缩 master switch (C6 启用)。 */
    private boolean compress = false;

    /** 触发压缩的 turn 阈值。 */
    private int compressThreshold = 6;

    /** topic shift 检测开关 (C5 启用)。 */
    private boolean topicShiftDetect = false;

    /** BGE-M3 cosine 阈值, 中文经验 0.5。 */
    private double topicShiftThreshold = 0.5;

    /** Tier B buffer window 大小 (保留最近 N turn 原文)。 */
    private int maxRecentTurns = 3;

    /**
     * G2 校准: rewrite 用的 LLM route。默认 primary(主 GLM) — fallback(DeepSeek) 的 condense 改写质量是 G2 2/20
     * 的主要瓶颈; 主 route 每次多花 ~200 token, 可用 rag.conversation.rewrite-route=fallback 切回省钱模式。
     */
    private String rewriteRoute = "primary";

    /**
     * 历史摘要使用的 LLM route。默认 fallback 以控制成本；当 fallback 不可用时可临时切 primary， 避免压缩熔断后 recentTurns 无界增长。
     */
    private String summaryRoute = "fallback";

    /** G2 校准: 喂 rewrite LLM 的 history turn 数(原硬编码 3, 指代常跨更远轮次)。 */
    private int rewriteHistoryTurns = 5;

    /** Redis TTL sliding (小时)。 */
    private int ttlHours = 24;
}
