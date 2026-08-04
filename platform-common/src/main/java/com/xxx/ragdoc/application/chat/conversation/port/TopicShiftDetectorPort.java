package com.xxx.ragdoc.application.chat.conversation.port;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;

/**
 * TopicShiftDetector 端口 (ADR-0011 §6.1 / Phase 1 / C5)。
 *
 * <p>用 BGE-M3 dense 嵌入拿 curr_query 与上 turn query 的余弦相似度, 低于阈值 (默认 0.5) 视为话题切换。
 *
 * <p>实现侧约定:
 *
 * <ul>
 *   <li>topic shift = true → ChatService 跳过 history, 走 stateless 单 turn 路径
 *   <li>null ctx (首次 turn) → 返 true (无 history, 等价 stateless)
 *   <li>embedding 失败 → 返 false (保守策略: 不判为 shift, 让 QueryContextualizer 兜底)
 *   <li>维度不匹配 (向量空间漂移) → 返 false 并打 warn 日志
 * </ul>
 */
public interface TopicShiftDetectorPort {

    boolean isTopicShift(String currQuery, ConversationContext ctx);
}
