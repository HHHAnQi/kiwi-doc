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
     * P0 修复(引用编号错位): history block 以此标记开头。
     *
     * <p>history block 曾作为 context 第 1 条 entry 参与 OpenAiCompatibleLlmClient 的 [n] 顺序编号,
     * LLM 眼中 [1]=对话历史而非第一条 evidence, 与前端按 citations 列表 i+1 的编号错一位。
     * LLM client 识别此前缀的 entry, 渲染为<b>不参与编号</b>的独立 history 段。
     */
    String HISTORY_BLOCK_MARKER = "<<CONVERSATION_HISTORY>>";

    /**
     * @param ctx 含 recentTurns 的会话上下文
     * @param topicShift true 时跳过 (调用方 TopicShiftDetector 判定)
     * @return 给 LLM 的 history 文本 (以 {@link #HISTORY_BLOCK_MARKER} 开头的 1 段拼接); 空字符串表示无 history 要塞
     */
    String buildHistoryBlock(ConversationContext ctx, boolean topicShift);
}
