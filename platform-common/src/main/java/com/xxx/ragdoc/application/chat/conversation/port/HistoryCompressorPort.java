package com.xxx.ragdoc.application.chat.conversation.port;

/**
 * HistoryCompressor 端口 (ADR-0011 §6.5 / Phase 1 / C6)。
 *
 * <p>异步触发: Tier S RollingSummary — 把 ConversationContext 中 near-window (3 轮)
 * 之外的 turns 摘要压成一条 {@code summaryTurn}, 释放 context 长度。
 *
 * <p>实现侧约定:
 *
 * <ul>
 *   <li>@Async 局部流池 (core=2, queue=100, DiscardPolicy)
 *   <li>quality gate: summary 字数 < 10 视为无效, 保留原 turns
 *   <li>debounce: 同 conversationId 在 N 秒内重复触发只跑一次
 *   <li>LLM 调用走 rewrite-llm CB + fallback (熔断 / 失败不挂)
 * </ul>
 *
 * <p>本接口方法 fire-and-forget: 返 void, 调用方不需等。压缩结果通过 ConversationStore.save 写回。
 */
public interface HistoryCompressorPort {

    /**
     * 触发压缩。
     *
     * @param conversationId 会话 ID; 不存在 / 已删 → silent no-op
     */
    void compress(String conversationId);
}
