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
 * <p>架构债清理: 本类从 infrastructure.conversation 包移到 application.chat (与 ChatMessages 同包),
 * 让 ChatService 不再依赖 infrastructure 层。配置 @ConditionalOnProperty / bean scan 不受影响。
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

    /** Redis TTL sliding (小时)。 */
    private int ttlHours = 24;
}
