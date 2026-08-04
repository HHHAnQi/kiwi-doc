package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 会话存储 NoOp 实现, ADR-0011 §3。
 *
 * <p>当 {@code rag.conversation.enabled=false} (默认) 时启用。该实现所有方法返回 empty / no-op,
 * ChatService 加载 ctx 时拿到 Optional.empty → 走 stateless 老路径, 完全无感知多轮能力。
 * 这是 **本 Phase 兼容性的根基**: feature flag OFF = 0 行为变化。
 *
 * <p>C2 添加的 {@code RedisConversationStore} 用 {@code @ConditionalOnProperty(... havingValue=true)}
 * 启用, 与本 Bean 互斥 (Spring 容器同一时刻只有一个 ConversationStore Bean)。
 *
 * <p>设计选择: 不额外加 {@code @ConditionalOnMissingBean} 兜底。这是 Spring 反模式 — 该注解依赖
 * Bean 注册顺序, 易出难调试问题。两个 {@code @ConditionalOnProperty} 互斥 (havingValue 互反) 足够。
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
