package com.xxx.ragdoc.application.chat.conversation.port;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;

/**
 * PromptAssembler 端口 (ADR-0011 §6.3 / Phase 1 / C3)。
 *
 * <p>把 ConversationContext.recentTurns 渲染成 LLM 可理解的 history 文本块, 同时执行 Tier C BufferWindow 取最近 3 轮 +
 * 极端兜底 hard cut (buffer > 5 时砍) + 防注入字符清洗。
 *
 * <p>实现侧约定:
 *
 * <ul>
 *   <li>{@code topicShift=true} 跳过 history, 直接返 "" (Tier C 抗污染)
 *   <li>{@code historyForcedTruncate=true} 时调 metrics.incrementHistoryForceTruncate
 * </ul>
 */
public interface PromptAssemblerPort {

    /**
     * @param ctx 含 recentTurns 的会话上下文
     * @param topicShift true 时跳过 (调用方 TopicShiftDetector 判定)
     * @return 给 LLM 的 history 文本 (1 段拼接); 空字符串表示无 history 要塞
     */
    String buildHistoryBlock(ConversationContext ctx, boolean topicShift);
}
