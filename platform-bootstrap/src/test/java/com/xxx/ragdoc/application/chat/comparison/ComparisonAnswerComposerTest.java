package com.xxx.ragdoc.application.chat.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.comparison.ComparisonEvidencePartitioner.ComparisonEvidenceSet;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** PR-6c.2: {@link ComparisonAnswerComposer} 单测 — 最重要: 单次 LLM 调用。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ComparisonAnswerComposer - PR-6c.2 单次 LLM 答案生成")
class ComparisonAnswerComposerTest {

    @Mock private ChatClient chatClient;
    private ComparisonAnswerComposer composer;

    @BeforeEach
    void setup() {
        composer = new ComparisonAnswerComposer(chatClient);
    }

    private ComparisonEvidenceSet setWithBothSides() {
        Evidence l =
                Evidence.of(
                        "tA",
                        1L,
                        10L,
                        "v1",
                        "left content",
                        0.9,
                        null,
                        "metadata_search",
                        Map.of("comparisonSide", "LEFT"));
        Evidence r =
                Evidence.of(
                        "tA",
                        2L,
                        20L,
                        "v1",
                        "right content",
                        0.9,
                        null,
                        "metadata_search",
                        Map.of("comparisonSide", "RIGHT"));
        return new ComparisonEvidenceSet(
                ComparisonTarget.of("L", "l"), List.of(l),
                ComparisonTarget.of("R", "r"), List.of(r));
    }

    @Test
    @DisplayName("compose: 只调用一次 ChatClient.chat (Revision §1.6 / §8.4 关键不变量)")
    void composeSingleLlmCall() throws Exception {
        when(chatClient.chat(anyString(), anyList())).thenReturn("对比答案文本");
        ComparisonAnswerComposer.ComparisonAnswer a =
                composer.compose("对比 v1 与 v2", setWithBothSides());
        verify(chatClient, times(1)).chat(anyString(), anyList());
        assertThat(a.text()).isEqualTo("对比答案文本");
        assertThat(a.usedEvidenceIds()).hasSize(2);
    }

    @Test
    @DisplayName("Prompt 不含 Agent Transcript / 预算 / 错误日志")
    void promptIsolated() throws Exception {
        ComparisonEvidenceSet set = setWithBothSides();
        List<String> context = ComparisonAnswerComposer.buildPromptContext("query", set);
        // 不应包含'Agent'/'budget'/'exception'等字段
        assertThat(context).noneMatch(s -> s.toLowerCase().contains("agentrunrecord"));
        assertThat(context).noneMatch(s -> s.toLowerCase().contains("budget"));
        assertThat(context).noneMatch(s -> s.toLowerCase().contains("exception"));
        // 应包含 LEFT TARGET / RIGHT TARGET 分块
        assertThat(context).anyMatch(s -> s.contains("LEFT TARGET"));
        assertThat(context).anyMatch(s -> s.contains("RIGHT TARGET"));
        // 应含 Evidence ID 引用前缀
        assertThat(context).anyMatch(s -> s.contains("[Evidence:"));
    }

    @Test
    @DisplayName("stream: 流式输出 DeltaEvent, 不调用 chat()")
    void streamEmitsDeltaEvents() throws Exception {
        when(chatClient.chatStream(anyString(), anyList())).thenReturn(Flux.just("a", "b", "c"));
        Flux<ChatStreamEvent> flux = composer.stream("query", setWithBothSides());
        StepVerifier.create(
                        flux.map(
                                ev -> ev instanceof ChatStreamEvent.DeltaEvent d ? d.delta() : "?"))
                .expectNext("a")
                .expectNext("b")
                .expectNext("c")
                .verifyComplete();
        verify(chatClient, times(0)).chat(anyString(), anyList());
    }
}
