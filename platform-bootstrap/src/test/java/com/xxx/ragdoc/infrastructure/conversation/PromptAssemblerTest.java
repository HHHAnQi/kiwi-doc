package com.xxx.ragdoc.infrastructure.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PromptAssembler 单测, ADR-0011 §7 §8.4。
 *
 * @author Phase 1 / C4
 */
class PromptAssemblerTest {

    private final RagdocMetrics metrics = mock(RagdocMetrics.class);
    private final PromptAssembler assembler = new PromptAssembler(metrics);

    @Test
    void ctx为null_应返回空串() {
        assertThat(assembler.buildHistoryBlock(null, false)).isEmpty();
    }

    @Test
    void ctx空_不是enabled_应返回空串() {
        ConversationContext ctx = ConversationContext.empty("conv-1");
        assertThat(assembler.buildHistoryBlock(ctx, false)).isEmpty();
    }

    @Test
    void 有summary无recentTurns_应只返回summary段() {
        ConversationContext ctx =
                ConversationContext.empty("conv-1")
                        .withCompression("用户问了 Sentinel QPS", List.of(), Instant.now());
        String block = assembler.buildHistoryBlock(ctx, false);
        assertThat(block).contains("[对话摘要]");
        assertThat(block).contains("Sentinel QPS");
        assertThat(block).doesNotContain("[最近对话]");
    }

    @Test
    void 有recentTurns无summary_应只返回对话段() {
        ConversationContext ctx =
                ConversationContext.empty("conv-1").appendTurn(turn("Q1", "A1"));
        String block = assembler.buildHistoryBlock(ctx, false);
        assertThat(block).contains("[最近对话]");
        assertThat(block).contains("Q: Q1");
        assertThat(block).doesNotContain("[对话摘要]");
    }

    @Test
    void summary加recentTurns_两段都应输出() {
        ConversationContext ctx =
                ConversationContext.empty("conv-1")
                        .withCompression("summary 历史", listOfTurns(3), Instant.now())
                        .appendTurn(turn("新问题", "新答案"));
        String block = assembler.buildHistoryBlock(ctx, false);
        assertThat(block).contains("[对话摘要]");
        assertThat(block).contains("[最近对话]");
    }

    @Test
    void topic_shift为true_应跳过recentTurns保留summary() {
        ConversationContext ctx =
                ConversationContext.empty("conv-1").appendTurn(turn("Q", "A"));
        String block = assembler.buildHistoryBlock(ctx, true);
        // 没有 summary, 也没有 recent turns (因为 topic shift) → 空串
        assertThat(block).isEmpty();
    }

    @Test
    void topic_shift为true但有summary_应返回summary但不返recentTurns() {
        ConversationContext ctx =
                ConversationContext.empty("conv-1")
                        .withCompression("历史背景", listOfTurns(3), Instant.now());
        String block = assembler.buildHistoryBlock(ctx, true);
        assertThat(block).contains("[对话摘要]");
        assertThat(block).doesNotContain("[最近对话]");
    }

    @Test
    void recentTurns超5应触发硬cut() {
        // 构造 8 turns → 超过 MAX_HISTORY_TRUNCATE=5
        ConversationContext ctx = ConversationContext.empty("conv-1");
        for (int i = 0; i < 8; i++) {
            ctx = ctx.appendTurn(turn("Q" + i, "A" + i));
        }
        String block = assembler.buildHistoryBlock(ctx, false);
        // Q0/Q1/Q2 被砍 (Q3-Q7 保留)
        assertThat(block).doesNotContain("Q: Q0");
        assertThat(block).doesNotContain("Q: Q1");
        assertThat(block).doesNotContain("Q: Q2");
        assertThat(block).contains("Q: Q3");
        assertThat(block).contains("Q: Q7");
        // 应该上调 force_truncate counter
        verify(metrics).incrementHistoryForceTruncate();
    }

    @Test
    void 长botAnswer应截断到200字以下() {
        String longAnswer = "x".repeat(500);
        ConversationContext ctx =
                ConversationContext.empty("conv-1").appendTurn(turn("Q", longAnswer));
        String block = assembler.buildHistoryBlock(ctx, false);
        assertThat(block).contains("...");
        // 截断后 botAnswer 区域 + prompt scaffold < 250
        assertThat(block.length()).isLessThan(300);
    }

    @Test
    void 空botAnswer_不应抛NPE_但应输出Q行() {
        // Turn 验证 state/botAnswer 非 null; 这里测空botAnswer 是否 graceful
        ConversationContext ctx =
                ConversationContext.empty("conv-1").appendTurn(turn("Q", ""));
        String block = assembler.buildHistoryBlock(ctx, false);
        assertThat(block).contains("Q: Q");
        // 空 botAnswer → "A:" + trim 输出 "A:" (建造时 builder 末尾 trim 掉了 trailing 空白)
        assertThat(block).contains("A:");
    }

    // ────────────────── helpers ──────────────────

    private static Turn turn(String q, String a) {
        return new Turn(q, a, List.of(1L), StateHint.OK, Instant.now());
    }

    private static List<Turn> listOfTurns(int n) {
        List<Turn> arr = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            arr.add(turn("Q" + i, "A" + i));
        }
        return arr;
    }
}
