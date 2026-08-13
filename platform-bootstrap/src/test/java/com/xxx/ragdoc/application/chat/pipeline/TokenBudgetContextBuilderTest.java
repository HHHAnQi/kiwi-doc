package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.agent.TokenEstimator;
import java.util.List;
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
}
