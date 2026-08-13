package com.xxx.ragdoc.infrastructure.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TopicShiftDetector 单测, ADR-0011 §5。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>ctx 空 / 第 1 turn → false (无 shift)
 *   <li>相似度正常 (≥ threshold) → false
 *   <li>相似度低 (< threshold) → true (话题切换)
 *   <li>embedding 失败 / null → false (不挂 chat)
 *   <li>向量维度不等 → false (无误判, cosine 返回 0)
 * </ul>
 *
 * @author Phase 1 / C5
 */
class TopicShiftDetectorTest {

    private EmbeddingClient embeddingClient;
    private ConversationProperties props;
    private RagdocMetrics metrics;
    private TopicShiftDetector detector;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        props = new ConversationProperties();
        props.setTopicShiftThreshold(0.5); // 默认值, 显式设置便于阅读
        metrics = mock(RagdocMetrics.class);
        detector = new TopicShiftDetector(embeddingClient, props, metrics);
    }

    @Test
    void ctx为null_应返回false() {
        boolean shift = detector.isTopicShift("Q", null);
        assertThat(shift).isFalse();
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void ctx第1turn无历史_应返回false() {
        ConversationContext ctx = ConversationContext.empty("c1"); // 0 turn
        boolean shift = detector.isTopicShift("第一问", ctx);
        assertThat(shift).isFalse();
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void 高相似度_应返回false() {
        // 两个高度相似的向量 (角度 0.1 弧度, 实际 cos ~ 0.99)
        when(embeddingClient.embed(any()))
                .thenReturn(emb(vec(1.0f, 0.1f)))
                .thenReturn(emb(vec(1.0f, 0.1f)));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Sentinel?"));
        boolean shift = detector.isTopicShift("Sentinel 详细?", ctx);

        assertThat(shift).isFalse();
        verify(metrics).incrementTopicShift("not_detected");
    }

    @Test
    void 低相似度_应返回true_话题切换() {
        // 横纵坐标垂直, cosine = 0 → 触发 shift
        when(embeddingClient.embed(any()))
                .thenReturn(emb(vec(1.0f, 0.0f)))
                .thenReturn(emb(vec(0.0f, 1.0f)));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Sentinel 流控?"));
        boolean shift = detector.isTopicShift("Nacos 配置中心是什么", ctx);

        assertThat(shift).isTrue();
        verify(metrics).incrementTopicShift("detected");
    }

    @Test
    void embed返回null_应返回false() {
        when(embeddingClient.embed(any())).thenReturn(null);

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Q"));
        boolean shift = detector.isTopicShift("新 Q", ctx);

        assertThat(shift).isFalse();
        verify(metrics).incrementTopicShift("embed_null");
    }

    @Test
    void embed抛异常_应返回false_不挂chat() {
        when(embeddingClient.embed(any())).thenThrow(new RuntimeException("BGE-M3 service down"));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Q"));
        boolean shift = detector.isTopicShift("新 Q", ctx);

        assertThat(shift).isFalse();
        verify(metrics).incrementTopicShift("detect_failed");
    }

    @Test
    void 向量维度不等_应抛异常fallback_false_metric_detect_failed() {
        // 维度不等 = embedding 异常 (BGE-M3 dim 恒 1024), 不让 cosine 算出 0 误判 shift
        when(embeddingClient.embed(any()))
                .thenReturn(emb(new float[] {1.0f, 0.0f, 0.0f}))
                .thenReturn(emb(new float[] {0.0f, 1.0f}));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Q"));
        boolean shift = detector.isTopicShift("Q2", ctx);

        assertThat(shift).isFalse();
        verify(metrics).incrementTopicShift("detect_failed");
    }

    @Test
    void 全零向量_防止除0_cosine返回0_应判shift() {
        when(embeddingClient.embed(any()))
                .thenReturn(emb(new float[] {0.0f, 0.0f}))
                .thenReturn(emb(new float[] {0.0f, 0.0f}));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Q"));
        boolean shift = detector.isTopicShift("Q2", ctx);

        // norm 为 0 → cosine 返 0 → 视为 shift (异常情况)
        assertThat(shift).isTrue();
    }

    @Test
    void 阈值边界_刚好0_5_应返回false() {
        props.setTopicShiftThreshold(0.5);
        // cos(60°) = 0.5, 但 < 0.5 才 shift; 等于阈值不算 shift
        when(embeddingClient.embed(any()))
                .thenReturn(emb(new float[] {1.0f, 0.0f}))
                .thenReturn(
                        emb(
                                new float[] {
                                    (float) Math.cos(Math.PI / 3), (float) Math.sin(Math.PI / 3)
                                }));

        ConversationContext ctx = ConversationContext.empty("c1").appendTurn(turn("Q"));
        boolean shift = detector.isTopicShift("Q2", ctx);

        // cos = 0.5 = threshold → 不算 shift (条件是 sim < threshold)
        assertThat(shift).isFalse();
    }

    // ────────────────── helpers ──────────────────

    private static Turn turn(String q) {
        return new Turn(q, "A", List.of(1L), StateHint.OK, Instant.now());
    }

    private static EmbeddingResult emb(float[] dense) {
        return new EmbeddingResult(dense, Map.of());
    }

    private static float[] vec(float... xs) {
        return xs;
    }
}
