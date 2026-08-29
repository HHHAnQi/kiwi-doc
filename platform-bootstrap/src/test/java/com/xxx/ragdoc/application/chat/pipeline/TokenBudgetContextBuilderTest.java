package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.agent.TokenEstimator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBudgetContextBuilderTest {
    private final TokenBudgetContextBuilder builder = new TokenBudgetContextBuilder();

    @Test
    void deterministicallyTruncatesAtTokenBoundary() {
        var first = builder.build(List.of("中文上下文", "abcdefghijklmnop", "不得进入"), 8);
        var second = builder.build(List.of("中文上下文", "abcdefghijklmnop", "不得进入"), 8);

        assertThat(first).isEqualTo(second);
        assertThat(first.truncated()).isTrue();
        assertThat(first.estimatedTokens()).isLessThanOrEqualTo(8);
        assertThat(first.context()).doesNotContain("不得进入");
        assertThat(first.context().stream().mapToInt(TokenEstimator::estimate).sum())
                .isLessThanOrEqualTo(8);
    }

    @Test
    void returnsImmutableInputOrderWhenWithinBudget() {
        var result = builder.build(List.of("A", "B"), 10);
        assertThat(result.context()).containsExactly("A", "B");
        assertThat(result.truncated()).isFalse();
    }

    // ─── P0 修复回归: 双闸门(token+char)与 citations 对齐 ───────────────

    @Test
    @DisplayName("P0: 字符闸门先于 token 闸门触发 → 尾部 entry 被 tail-drop, keptCount 反映实际保留")
    void charCapDropsTailBeforeTokenBudget() {
        // 3 个 entry, token 都很小, 但 char 总量超 20 → 第 3 个被整段丢
        // char 闸门=8: "aaaa"(4)+"bbbb"(4) 恰好装满, "cccc" 整段 tail-drop
        TokenBudgetContextBuilder.BuildResult r =
                new TokenBudgetContextBuilder().build(List.of("aaaa", "bbbb", "cccc"), 10_000, 8);
        assertThat(r.context()).containsExactly("aaaa", "bbbb");
        assertThat(r.truncated()).isTrue();
        // 调用方据此把 citations 截到 kept.size(), [n] 编号与卡片保持对齐
        assertThat(r.context()).hasSize(2);
    }

    @Test
    @DisplayName("P0: 字符预算只够截半个 entry → 保留前缀且标记 truncated")
    void charCapPartiallyTruncatesLastEntry() {
        TokenBudgetContextBuilder.BuildResult r =
                new TokenBudgetContextBuilder().build(List.of("aaaa", "bbbb"), 10_000, 6);
        assertThat(r.context()).containsExactly("aaaa", "bb");
        assertThat(r.truncated()).isTrue();
    }
}
