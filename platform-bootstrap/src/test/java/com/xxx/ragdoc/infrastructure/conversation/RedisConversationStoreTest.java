package com.xxx.ragdoc.infrastructure.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.domain.shared.StateHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RedisConversationStore 单测, ADR-0011 §3 工程纪律核心.
 *
 * <p>不依赖 Redis 实例 (用 Mockito mock StringRedisTemplate). 真容器 round-trip 留给 V2 在加 testcontainers IT
 * 时覆盖. 本测试聚焦"异常 fallback 不挂 chat"这条企业级 RAG vs demo 的分水岭.
 *
 * @author Phase 1 / C2
 */
class RedisConversationStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConversationProperties props;
    private RedisConversationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        props = new ConversationProperties();
        props.setTtlHours(24);
        store = new RedisConversationStore(redis, props);
    }

    @Test
    void findById_正常应反序列化返回ctx() {
        ConversationContext ctx = sampleCtx();
        String json = serialize(ctx);
        when(valueOps.get("ragdoc:conv:" + ctx.conversationId())).thenReturn(json);

        Optional<ConversationContext> loaded = store.findById(ctx.conversationId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().conversationId()).isEqualTo(ctx.conversationId());
        assertThat(loaded.get().totalTurnCount()).isEqualTo(ctx.totalTurnCount());
    }

    @Test
    void findById_Redis返回null_应返回empty() {
        when(valueOps.get(any())).thenReturn(null);

        Optional<ConversationContext> loaded = store.findById("missing");

        assertThat(loaded).isEmpty();
    }

    @Test
    void findById_Redis抛异常_应返回empty不抛() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("connection refused"));

        Optional<ConversationContext> loaded = store.findById("conv-1");

        // 不抛异常 → fallback stateless
        assertThat(loaded).isEmpty();
    }

    @Test
    void findById_反序列化失败_应返回empty不抛() {
        when(valueOps.get(any())).thenReturn("{ 不是有效 JSON");

        Optional<ConversationContext> loaded = store.findById("conv-1");

        assertThat(loaded).isEmpty();
    }

    @Test
    void save_正常应序列化并set带TTL() {
        ConversationContext ctx = sampleCtx();
        store.save(ctx);
        verify(valueOps)
                .set(
                        eq("ragdoc:conv:" + ctx.conversationId()),
                        any(String.class),
                        any(java.time.Duration.class));
    }

    @Test
    void save_Redis异常_应静默不抛() {
        ConversationContext ctx = sampleCtx();
        doThrow(new RuntimeException("connect timeout"))
                .when(valueOps)
                .set(any(), any(), any(java.time.Duration.class));

        // 不应抛
        store.save(ctx);

        // 验证确实调过 (silently swallowed)
        verify(valueOps).set(any(), any(), any(java.time.Duration.class));
    }

    @Test
    void clear_正常应调delete() {
        store.clear("conv-1");
        verify(redis).delete(eq("ragdoc:conv:conv-1"));
    }

    @Test
    void clear_Redis异常_应静默不抛() {
        doThrow(new RuntimeException("fail")).when(redis).delete(eq("ragdoc:conv:conv-1"));
        store.clear("conv-1"); // 不抛
    }

    @Test
    void exists_正常存在_returnsTrue() {
        when(redis.hasKey("ragdoc:conv:conv-1")).thenReturn(true);
        assertThat(store.exists("conv-1")).isTrue();
    }

    @Test
    void exists_异常_returnsFalse() {
        when(redis.hasKey(eq("ragdoc:conv:conv-1"))).thenThrow(new RuntimeException("fail"));
        assertThat(store.exists("conv-1")).isFalse();
    }

    // ────────────────── helpers ──────────────────

    private static ConversationContext sampleCtx() {
        ConversationContext.Turn turn =
                new ConversationContext.Turn(
                        "Sentinel 默认 QPS?", "~10", List.of(1L), StateHint.OK, Instant.now());
        return ConversationContext.empty("conv-test").appendTurn(turn);
    }

    private String serialize(ConversationContext ctx) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            m.configure(
                    com.fasterxml.jackson.databind.DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES,
                    false);
            return m.writeValueAsString(ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
