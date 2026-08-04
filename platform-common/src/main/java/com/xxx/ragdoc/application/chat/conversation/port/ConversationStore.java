package com.xxx.ragdoc.application.chat.conversation.port;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import java.util.Optional;

/**
 * 会话上下文存储端口, ADR-0011 §3。
 *
 * <p>两个实现按 feature flag 选择:
 *
 * <ul>
 *   <li>{@code NoOpConversationStore} (默认, dev / 全 OFF): 返回 empty → ChatService 走 stateless 老路径
 *   <li>{@code RedisConversationStore} ({@code @ConditionalOnProperty(prefix="rag.conversation",
 *       name="enabled", havingValue="true")}): Redis Hash + 24h TTL sliding, C2 实现
 * </ul>
 *
 * <h3>工程纪律 (硬约束)</h3>
 *
 * <ul>
 *   <li>任何 implement 必须保证"读 / 写异常不挂 chat 主路径"。即:
 *       <ul>
 *         <li>findById 异常 → 返回 {@link Optional#empty()}, 由 ChatService 走 stateless
 *         <li>save 异常 → silent log, 用户 chat 仍正常返回(本 turn 在内存继续用)
 *         <li>clear 异常 → silent log
 *       </ul>
 *   <li>save 必须 refresh 24h TTL(sliding), 活跃会话不会过期
 * </ul>
 *
 * <p>调用方约束(ChatService C4 实现):
 *
 * <ol>
 *   <li>READ 唯一点: chat() / chatStream() 入口
 *   <li>WRITE 唯一点: 仅当 stateHint == OK 时调 save (G3 抗污染硬 gate)
 * </ol>
 *
 * @author Phase 1 / C1 (ADR-0011)
 */
public interface ConversationStore {

    /**
     * 按 conversationId 加载 ctx。
     *
     * @return 不存在 / TTL 过期 / Redis 挂 → {@link Optional#empty()}
     */
    Optional<ConversationContext> findById(String conversationId);

    /**
     * Upsert + refresh TTL (sliding)。 异常时静默, 不挂 chat。
     *
     * <p>调用方约束: 仅当 turn state == OK 才允许调本方法。
     */
    void save(ConversationContext ctx);

    /** 用户 "新对话"按钮 / topic shift 强 reset (本 Phase 不强制 clear, 留接口)。 */
    void clear(String conversationId);

    /** 仅检查存在性, 不反序列化 (TTL 探活 / 监控用)。 */
    boolean exists(String conversationId);
}
