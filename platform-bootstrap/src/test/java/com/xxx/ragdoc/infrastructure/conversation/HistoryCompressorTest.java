package com.xxx.ragdoc.infrastructure.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HistoryCompressor 单测, ADR-0011 §6 + §9。
 *
 * <p>覆盖 5 个 outcome 路径:
 *
 * <ul>
 *   <li>no_ctx: store 返回 empty → skip + metric no_ctx
 *   <li>skipped_no_need: ctx 未达阈值 → skip + metric skipped_no_need (debounce)
 *   <li>ok: 摘要成功 → store.save + ctx 含 summary/3 turns (其余 old 已归档)
 *   <li>failed: LLM 抛异常 → metric failed, store 不 save
 *   <li>invalid: 摘要长度 &lt; 10 → metric invalid, store 不 save
 * </ul>
 *
 * @author Phase 1 / C6
 */
class HistoryCompressorTest {

    private OpenAiCompatibleLlmClient summaryClient;
    private LlmRouter router;
    private ConversationStore store;
    private ConversationProperties props;
    private RagdocMetrics metrics;
    private HistoryCompressor compressor;

    @BeforeEach
    void setUp() {
        summaryClient = mock(OpenAiCompatibleLlmClient.class);
        router = mock(LlmRouter.class);
        when(router.getRouteClient("fallback")).thenReturn(summaryClient);
        store = mock(ConversationStore.class);
        props = new ConversationProperties();
        props.setCompressThreshold(6);
        props.setMaxRecentTurns(3);
        metrics = mock(RagdocMetrics.class);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        compressor = new HistoryCompressor(router, registry, store, props, metrics);
    }

    @Test
    void ctx不存在_应返no_ctx_不调LLM() {
        when(store.findById(anyString())).thenReturn(Optional.empty());

        compressor.compress("c1");

        verifyNoInteractions(summaryClient);
        verify(store, never()).save(any());
        verify(metrics).incrementCompression("no_ctx");
    }

    @Test
    void store读异常_应返load_failed_不调LLM() {
        when(store.findById(anyString())).thenThrow(new RuntimeException("redis down"));

        compressor.compress("c1");

        verifyNoInteractions(summaryClient);
        verify(metrics).incrementCompression("load_failed");
    }

    @Test
    void ctx未达阈值_应返skipped_no_need() {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(3)));

        compressor.compress("c1");

        verifyNoInteractions(summaryClient);
        verify(metrics).incrementCompression("skipped_no_need");
    }

    @Test
    void LLM正常_应压summary保留近3turn() throws Exception {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(6)));
        when(summaryClient.chat(anyString(), anyList())).thenReturn("用户问了 Sentinel 与 Hystrix QPS");

        compressor.compress("c1");

        // verify store.save 拿到的 ctx 有 summary + 3 turns
        org.mockito.ArgumentCaptor<ConversationContext> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationContext.class);
        verify(store).save(captor.capture());
        ConversationContext saved = captor.getValue();
        assertThat(saved.rollingSummary()).contains("Sentinel");
        assertThat(saved.recentTurns()).hasSize(3); // 保留最近 3, 老的 3 进摘要了
        verify(metrics).incrementCompression("ok");
    }

    @Test
    void LLM抛异常_应返failed_不save() throws Exception {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(6)));
        when(summaryClient.chat(anyString(), anyList()))
                .thenThrow(new RuntimeException("DeepSeek timeout"));

        compressor.compress("c1");

        verify(store, never()).save(any());
        verify(metrics).incrementCompression("failed");
    }

    @Test
    void 摘要太短_应quality_rejected_不save() throws Exception {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(6)));
        when(summaryClient.chat(anyString(), anyList())).thenReturn("短"); // < 10 char

        compressor.compress("c1");

        verify(store, never()).save(any());
        verify(metrics).incrementCompression("invalid");
    }

    @Test
    void 摘要null_应quality_rejected() throws Exception {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(6)));
        when(summaryClient.chat(anyString(), anyList())).thenReturn(null);

        compressor.compress("c1");

        verify(store, never()).save(any());
        verify(metrics).incrementCompression("invalid");
    }

    @Test
    void save异常_应返save_failed_不挂线程() throws Exception {
        when(store.findById(anyString())).thenReturn(Optional.of(ctxWithTurns(6)));
        when(summaryClient.chat(anyString(), anyList())).thenReturn("这是有效的摘要文本啊");
        doThrow(new RuntimeException("redis down"))
                .when(store).save(any());

        // 不应抛 (后台线程吞掉)
        compressor.compress("c1");

        verify(metrics).incrementCompression("save_failed");
    }

    // ────────────────── helpers ──────────────────

    private static ConversationContext ctxWithTurns(int n) {
        ConversationContext ctx = ConversationContext.empty("c1");
        for (int i = 0; i < n; i++) {
            ctx = ctx.appendTurn(turn("Q" + i, "A" + i));
        }
        return ctx;
    }

    private static Turn turn(String q, String a) {
        return new Turn(q, a, List.of(1L), StateHint.OK, Instant.now());
    }
}
