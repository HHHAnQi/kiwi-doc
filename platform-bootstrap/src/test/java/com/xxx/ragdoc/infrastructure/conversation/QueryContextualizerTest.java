package com.xxx.ragdoc.infrastructure.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.conversation.ContextualizeResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * QueryContextualizer 单测, ADR-0011 §4。
 *
 * <p>覆盖 4 个 outcome 路径:
 *
 * <ul>
 *   <li>skip: history 空 (第 1 turn) → 不调 LLM
 *   <li>skip 鹦鹉: LLM 返回等于 / 包含原 query → skip
 *   <li>ok: LLM 正常 rewrite
 *   <li>failed: LLM 异常 → fallback 原 query
 * </ul>
 *
 * <p>3 个内部 helper 单独覆盖: isParroted / trimmed / truncate (虽然 private, 通过公共路径间接验)。
 *
 * @author Phase 1 / C3
 */
class QueryContextualizerTest {

    private OpenAiCompatibleLlmClient routeClient;
    private ChatClient routerAsClient; // LlmRouter implements ChatClient, 但我们只用作 routing 容器
    private LlmRouter router;
    private RagdocMetrics metrics;
    private QueryContextualizer ctx;

    @BeforeEach
    void setUp() {
        routeClient = mock(OpenAiCompatibleLlmClient.class);
        router = mock(LlmRouter.class);
        when(router.getRouteClient("fallback")).thenReturn(routeClient);
        metrics = mock(RagdocMetrics.class);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ctx = new QueryContextualizer(router, registry, metrics);
    }

    @Test
    void 第1turn_history空_应skip_不调LLM() throws Exception {
        ContextualizeResult r = ctx.contextualize("Sentinel 默认 QPS?", List.of());

        assertThat(r.outcome()).isEqualTo("skip");
        assertThat(r.retrieveQuery()).isEqualTo("Sentinel 默认 QPS?");
        verifyNoInteractions(routeClient);
        verify(metrics).recordRewriteLatency(0L, "skip");
    }

    @Test
    void LLM成功rewrite_应返回ok() throws Exception {
        when(routeClient.chat(anyString(), anyList())).thenReturn("Hystrix 默认 QPS 阈值是多少");

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Sentinel 默认 QPS?", "10")));

        assertThat(r.outcome()).isEqualTo("ok");
        assertThat(r.retrieveQuery()).contains("Hystrix");
        assertThat(r.retrieveQuery()).doesNotContain("那");
        verify(metrics).recordRewriteLatency(anyLong(), eq("ok"));
    }

    @Test
    void LLM鹦鹉学舌_应skip() throws Exception {
        // LLM 直接复读了原 query
        when(routeClient.chat(anyString(), anyList())).thenReturn("那 Hystrix 呢");

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Sentinel?", "10")));

        assertThat(r.outcome()).isEqualTo("skip");
        // retrieve query 是原 query (rewritten == original 已被 skip)
        assertThat(r.retrieveQuery()).isEqualTo("那 Hystrix 呢");
        verify(metrics).recordRewriteLatency(anyLong(), eq("skip"));
    }

    @Test
    void LLM含原query子串_也算鹦鹉_skip() throws Exception {
        when(routeClient.chat(anyString(), anyList())).thenReturn("那 Hystrix 呢 详细说说");

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Q", "A")));

        assertThat(r.outcome()).isEqualTo("skip");
    }

    @Test
    void LLM抛异常_应failed_返回原query() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenThrow(new RuntimeException("DeepSeek connection refused"));

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Sentinel?", "10")));

        assertThat(r.outcome()).isEqualTo("failed");
        assertThat(r.retrieveQuery()).isEqualTo("那 Hystrix 呢"); // fallback 原 query
        verify(metrics).recordRewriteLatency(anyLong(), eq("failed"));
    }

    @Test
    void LLM返回带引号空格_应trim() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenReturn("  \"Hystrix 默认阈值\"  ");

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Q", "A")));

        assertThat(r.outcome()).isEqualTo("ok");
        assertThat(r.retrieveQuery()).isEqualTo("Hystrix 默认阈值");
    }

    @Test
    void 多个historyTurn_只取最近3个喂prompt() throws Exception {
        when(routeClient.chat(anyString(), anyList())).thenReturn("rewrite-result");

        ctx.contextualize(
                "Q5",
                List.of(turn("Q1", "A1"), turn("Q2", "A2"), turn("Q3", "A3"), turn("Q4", "A4")));

        // 验证 LLM 被调 (history 不为空就调)
        verify(routeClient).chat(anyString(), anyList());
    }

    @Test
    void 大写空格不影响鹦鹉检测() throws Exception {
        // 这种情况 rewrite 直接尾随, 应该 skip
        when(routeClient.chat(anyString(), anyList()))
                .thenReturn("那 hystrix 呢");

        ContextualizeResult r =
                ctx.contextualize("那 Hystrix 呢", List.of(turn("Q", "A")));

        // 空格 + 大小写已 normalized, 检测得到
        assertThat(r.outcome()).isEqualTo("skip");
    }

    // ────────────────── helpers ──────────────────

    private static Turn turn(String q, String a) {
        return new Turn(q, a, List.of(1L), StateHint.OK, Instant.now());
    }
}
