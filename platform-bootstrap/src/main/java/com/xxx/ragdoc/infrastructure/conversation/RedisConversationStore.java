package com.xxx.ragdoc.infrastructure.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话上下文 Redis 实现, ADR-0011 §3。
 *
 * <p>{@code rag.conversation.enabled=true} 时启用。否则 NoOpConversationStore (C1) 接管, 二者互斥。
 *
 * <h3>存储 schema</h3>
 *
 * <pre>
 *   key:    ragdoc:conv:{conversationId}
 *   type:   Redis String (JSON)
 *   value:  ConversationContext 序列化 (含 conversationId / recentTurns / rollingSummary / ...)
 *   TTL:    props.ttlHours (默认 24h) sliding — 每次 save 刷新
 * </pre>
 *
 * <h3>工程纪律 (硬约束 from port javadoc)</h3>
 *
 * <ul>
 *   <li>findById 任意异常 → Optional.empty() + log (ChatService 走 stateless)
 *   <li>save 任意异常 → silent log (用户 chat 仍正常返回, 本 turn 在内存继续用)
 *   <li>clear 任意异常 → silent log
 *   <li>exists 任意异常 → false (TTL 探活降级)
 * </ul>
 *
 * <p>chat 主路径永远不被 store 异常挂死, 这是企业级 RAG 与 demo 的分水岭 (ADR-0011 §8.4)。
 *
 * @author Phase 1 / C2 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "rag.conversation",
        name = "enabled",
        havingValue = "true")
public class RedisConversationStore implements ConversationStore {

    private static final String KEY_PREFIX = "ragdoc:conv:";

    private final StringRedisTemplate redis;
    private final ConversationProperties props;
    private final ObjectMapper mapper;

    public RedisConversationStore(
            StringRedisTemplate redis, ConversationProperties props) {
        this.redis = redis;
        this.props = props;
        // JavaTimeModule for Instant (de)serialization.
        // disable FAIL_ON_UNKNOWN_PROPERTIES: isEnabled() 会被序列化为 "enabled":true 但无对应 setter,
        // 反序列化需要容忍它 (无需维护该字段, 是isEnabled()派生的)。
        this.mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .configure(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .FAIL_ON_UNKNOWN_PROPERTIES,
                                false);
        log.info(
                "ConversationStore=Redis, ttl={}h, compressThreshold={}, maxRecentTurns={}",
                props.getTtlHours(),
                props.getCompressThreshold(),
                props.getMaxRecentTurns());
    }

    @Override
    public Optional<ConversationContext> findById(String conversationId) {
        try {
            String json = redis.opsForValue().get(key(conversationId));
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readValue(json, ConversationContext.class));
        } catch (Exception e) {
            // 反序列化失败 / Redis 异常 → 不挂 chat, 走 stateless
            log.warn(
                    "conv.findById_failed id={}, reason={} — fallback stateless",
                    conversationId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(ConversationContext ctx) {
        String json;
        try {
            json = mapper.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            // 序列化失败 = 数据丢失 (本 turn 永远无法写回 Redis, 下次 chat 拿不到)
            // 显著 error 而不是 silent, 配合 Grafana alert (C8)
            log.error(
                    "conv.save_serialize_failed id={} totalTurns={} — data loss risk",
                    ctx.conversationId(),
                    ctx.totalTurnCount(),
                    e);
            return;
        }
        try {
            redis.opsForValue().set(key(ctx.conversationId()), json, ttl());
        } catch (Exception e) {
            // Redis 异常: silent warn, 用户不见, 本 turn 内存里继续可用 (下次 save 重试)
            log.warn(
                    "conv.save_failed id={}, reason={} — will retry on next turn",
                    ctx.conversationId(),
                    e.getMessage());
        }
    }

    @Override
    public void clear(String conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            log.warn("conv.clear_failed id={}, reason={}", conversationId, e.getMessage());
        }
    }

    @Override
    public boolean exists(String conversationId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(conversationId)));
        } catch (Exception e) {
            log.warn("conv.exists_failed id={}, reason={}", conversationId, e.getMessage());
            return false;
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private Duration ttl() {
        return Duration.ofHours(props.getTtlHours());
    }
}
