package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.conversation.port.PromptAssemblerPort;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 多轮对话 prompt 拼装, ADR-0011 §7 + §8.4。
 *
 * <p>设计选择: <b>不改 {@code ChatClient#chat(query, context)} 签名</b>, 而是把 history+summary
 * 压成一个 "history block" 字符串, 作为 retrieved context 列表的<b>第一条</b> entry 喂给 LLM。
 * LLM 把它当成 context 的一部分读, 主 GLM-4-plus 完全不感知多轮。这种最小侵入策略让:
 *
 * <ul>
 *   <li>{@code OpenAiCompatibleLlmClient} 不动一行代码 (A/B baseline 零回归风险)
 *   <li>{@code DashScopeChatClient} 也不动 (老路径兼容保留)
 *   <li>chat() 接口返回的 answer 不被 history 的渲染细节污染 (history 不进 user query, 进 context)
 * </ul>
 *
 * <h3>prompt ordering (Anthropic 2025.06 context engineering 推荐)</h3>
 *
 * <pre>
 *   [SYSTEM]   role + 规则 (在 OpenAiCompatibleLlmClient 已处理)
 *   [HISTORY]  rollingSummary + 最近 N turn 原文 (本类组装, 作 context 第 1 entry)
 *   [RETRIEVED] chunks (ChatService 加在 context 列表后面)
 *   [USER]     cmd.query() (永远原文, OpenAiCompatibleLlmClient 拼)
 * </pre>
 *
 * <h3>硬 cut 兜底 (ADR-0011 §8.4)</h3>
 *
 * 极端情况: 压缩失败 + 用户连发 20 turn → buffer 不归档 → history block 超 token budget。
 * 本类直接截断为 max 5 turn (从老的砍) + 上调 {@link RagdocMetrics#incrementHistoryForceTruncate}。
 *
 * @author Phase 1 / C4 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "rag.conversation",
        name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class PromptAssembler implements PromptAssemblerPort {

    /** 硬 cut: prompt 里 history 最多多少 turn (极端情况兜底, 见 §8.4)。 */
    private static final int MAX_HISTORY_TRUNCATE = 5;

    /** history block 里 botAnswer 截断多少字 (避免长答案膨胀 token)。 */
    private static final int ANSWER_TRUNCATE_CHARS = 200;

    private final RagdocMetrics metrics;

    /**
     * 构建 history block (rollingSummary + 最近 N turn 原文)。空 ctx / topic shift 时返回空串。
     *
     * @param ctx ConversationContext, 可能为 null (ConversationStore.install 异常 fallback 时)
     * @param topicShift topic shift 标记, true 时跳过 history rewrite (但仍可保留 summary 概念)
     * @return history block 字符串; 空串表示不附加 (ChatService 直接跳过)
     */
    public String buildHistoryBlock(ConversationContext ctx, boolean topicShift) {
        if (ctx == null || !ctx.isEnabled()) return "";

        StringBuilder sb = new StringBuilder();

        // 1) Rolling Summary (Tier S, 概念级), 即使 topic shift 也保留 (作为弱远期背景)
        if (ctx.rollingSummary() != null && !ctx.rollingSummary().isBlank()) {
            sb.append("[对话摘要] (历史背景)\n").append(ctx.rollingSummary()).append("\n\n");
        }

        // 2) Recent turns (Tier B, 指代还原用), topic shift 时跳过
        if (!topicShift && ctx.recentTurns() != null && !ctx.recentTurns().isEmpty()) {
            List<Turn> turns = ctx.recentTurns();
            int overflow = turns.size() - MAX_HISTORY_TRUNCATE;
            if (overflow > 0) {
                log.warn(
                        "history.force_truncate size={} > max={}",
                        turns.size(),
                        MAX_HISTORY_TRUNCATE);
                metrics.incrementHistoryForceTruncate();
                turns = turns.subList(overflow, turns.size()); // 保留最近 N
            }

            sb.append("[最近对话]\n");
            for (Turn t : turns) {
                sb.append("Q: ").append(t.userQuery()).append("\n");
                sb.append("A: ").append(truncate(t.botAnswer(), ANSWER_TRUNCATE_CHARS)).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
