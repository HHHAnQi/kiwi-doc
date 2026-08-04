package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 会话存储 NoOp 实现, ADR-0011 §3。
 *
 * <p>当 {@code rag.conversation.enabled=false} (默认) 且没有其他 ConversationStore Bean 存在时启用。
 * 该实现所有方法返回 empty / no-op, ChatService 加载 ctx 时拿到 Optional.empty → 走 stateless 老路径,
 * 完全无感知多轮能力。这是 **本 Phase 兼容性的根基**: feature flag OFF = 0 行为变化。
 *
 * <p>C2 将添加 {@code RedisConversationStore}, 用 {@code @ConditionalOnProperty(prefix="rag.conversation",
 * name="enabled", havingValue="true")} 启用, 不会与本 Bean 冲突。
 *
 * <p>双 {@code @Conditional} 设计 (AND 关系):
 *
 * <ul>
 *   <li>{@code @ConditionalOnProperty} (prefix=rag.conversation, name=enabled, havingValue=false, matchIfMissing=true) —
 *       flag OFF 或缺失时启用
 *   <li>{@code @ConditionalOnMissingBean} — 兜底保护: 万一别的实现同时启用, 让路上 (Spring 启动报错比
 *       静默选错更安全)
 * </ul>
 *
 * @author Phase 1 / C1 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "rag.conversation",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
@ConditionalOnMissingBean(ConversationStore.class)
public class NoOpConversationStore implements ConversationStore {

    public NoOpConversationStore() {
        log.info("ConversationStore=NoOp (stateless behavior, rag.conversation.enabled=false)");
    }

    @Override
    public Optional<ConversationContext> findById(String conversationId) {
        return Optional.empty();
    }

    @Override
    public void save(ConversationContext ctx) {
        // no-op: 不挂 chat
    }

    @Override
    public void clear(String conversationId) {
        // no-op
    }

    @Override
    public boolean exists(String conversationId) {
        return false;
    }
}
