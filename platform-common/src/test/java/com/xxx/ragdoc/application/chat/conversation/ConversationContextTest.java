package com.xxx.ragdoc.application.chat.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xxx.ragdoc.domain.shared.StateHint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ConversationContext 单测, ADR-0011 §1。
 *
 * <p>核心覆盖 4 条规则:
 *
 * <ol>
 *   <li>不可变设计: appendTurn / withCompression 返回新实例
 *   <li>isEnabled 判定: 最近 turn 或 summary 任一非空即为 true
 *   <li>needsCompression debounce: 阈值满足 + 距上次压缩 ≥1min 才返回 true (G4 防 LLM 重复调用)
 *   <li>G3 抗污染 isWritable: 只有 OK 允许写
 * </ol>
 *
 * @author Phase 1 / C1
 */
class ConversationContextTest {

    @Test
    void empty_应返回合法disabled状态() {
        ConversationContext ctx = ConversationContext.empty("conv-1");
        assertThat(ctx.conversationId()).isEqualTo("conv-1");
        assertThat(ctx.isEnabled()).isFalse();
        assertThat(ctx.recentTurns()).isEmpty();
        assertThat(ctx.rollingSummary()).isNull();
        assertThat(ctx.totalTurnCount()).isZero();
    }

    @Test
    void appendTurn_应返回新实例_原ctx不变() {
        ConversationContext ctx = ConversationContext.empty("conv-1");
        ConversationContext.Turn t = okTurn("Q1", "A1");

        ConversationContext updated = ctx.appendTurn(t);

        // 不可变: 原 ctx 仍 empty
        assertThat(ctx.recentTurns()).isEmpty();
        assertThat(ctx.totalTurnCount()).isZero();

        // 新实例持 new turn
        assertThat(updated.recentTurns()).hasSize(1);
        assertThat(updated.totalTurnCount()).isEqualTo(1);
        assertThat(updated.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_summary非空也为true() {
        ConversationContext ctx =
                new ConversationContext(
                        "conv-1",
                        null,
                        null,
                        List.of(),
                        "用户问了 Sentinel QPS",
                        5,
                        Instant.now(),
                        Instant.now(),
                        null);
        assertThat(ctx.isEnabled()).isTrue();
    }

    @Test
    void needsCompression_阈值未满足_false() {
        ConversationContext ctx = ctxWithTurns(5, null); // 5 < 6
        assertThat(ctx.needsCompression(6)).isFalse();
    }

    @Test
    void needsCompression_首次满足阈值_true() {
        ConversationContext ctx = ctxWithTurns(6, null); // summaryUpdatedAt=null
        assertThat(ctx.needsCompression(6)).isTrue();
    }

    @Test
    void needsCompression_debounce内_false() {
        // 距上次压缩 < 1min → false
        Instant justNow = Instant.now().minusSeconds(30);
        ConversationContext ctx = ctxWithTurns(8, justNow);
        assertThat(ctx.needsCompression(6)).isFalse();
    }

    @Test
    void needsCompression_debounce外_true() {
        Instant oneMinAgo = Instant.now().minus(Duration.ofMinutes(2));
        ConversationContext ctx = ctxWithTurns(8, oneMinAgo);
        assertThat(ctx.needsCompression(6)).isTrue();
    }

    @Test
    void withCompression_应保留keepTurns和更新summary() {
        ConversationContext ctx = ctxWithTurns(6, null);
        Instant compressedAt = Instant.now();

        ConversationContext compressed =
                ctx.withCompression("新摘要", ctx.recentTurns().subList(3, 6), compressedAt);

        assertThat(compressed.rollingSummary()).isEqualTo("新摘要");
        assertThat(compressed.recentTurns()).hasSize(3);
        assertThat(compressed.summaryUpdatedAt()).isEqualTo(compressedAt);
        // 总 turn 数不变 (审计用)
        assertThat(compressed.totalTurnCount()).isEqualTo(ctx.totalTurnCount());
    }

    @Test
    void isWritable_只允许OK() {
        assertThat(ConversationContext.isWritable(StateHint.OK)).isTrue();
        assertThat(ConversationContext.isWritable(StateHint.LLM_DEGRADED)).isFalse();
        assertThat(ConversationContext.isWritable(StateHint.NO_RECALL)).isFalse();
        assertThat(ConversationContext.isWritable(StateHint.EMPTY_KB)).isFalse();
    }

    @Test
    void Turn_citedChunkIds_null_应_normalize_为emptyList() {
        ConversationContext.Turn t =
                new ConversationContext.Turn("Q", "A", null, StateHint.OK, Instant.now());
        assertThat(t.citedChunkIds()).isNotNull().isEmpty();
    }

    @Test
    void Turn_null必填字段应抛NPE() {
        assertThatThrownBy(
                        () ->
                                new ConversationContext.Turn(
                                        null, "A", List.of(), StateHint.OK, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullConversationId应抛NPE() {
        assertThatThrownBy(
                        () ->
                                new ConversationContext(
                                        null, "u", null, List.of(), null, 0,
                                        Instant.now(), Instant.now(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recentTurns_应是不可变list() {
        ConversationContext ctx = ctxWithTurns(3, null);
        assertThatThrownBy(() -> ctx.recentTurns().add(okTurn("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ────────────────── helpers ──────────────────

    private static ConversationContext.Turn okTurn(String q, String a) {
        return new ConversationContext.Turn(q, a, List.of(1L), StateHint.OK, Instant.now());
    }

    /**
     * 构造有 N 个 OK turn 的 ctx, summaryUpdatedAt 控制为传入值。
     *
     * <p>用 {@link ConversationContext#withCompression} 路径植入 summaryUpdatedAt — 这是该字段
     * 唯一可写的合法 path(实际生产中只有异步压缩任务会写)。保留所有 turn 不丢, 测试只看
     * needsCompression 阈值判定。
     */
    private static ConversationContext ctxWithTurns(int n, Instant summaryUpdatedAt) {
        ConversationContext ctx = ConversationContext.empty("conv-test");
        for (int i = 0; i < n; i++) {
            ctx = ctx.appendTurn(okTurn("Q" + i, "A" + i));
        }
        if (summaryUpdatedAt != null) {
            String existingSummary = ctx.rollingSummary();
            ctx = ctx.withCompression(existingSummary, ctx.recentTurns(), summaryUpdatedAt);
        }
        return ctx;
    }
}
