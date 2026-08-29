package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** P1-B: composer LLM 调用点指标(component=composer), 每次真实调用恰一笔。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P1-B Agent指标接线 - composer LLM调用点")
class ComposerMetricsWiringTest {

    @Mock private ChatClient chatClient;
    @Mock private MetricsPort metrics;

    @Test
    @DisplayName("compose 真实调用 chatClient.chat → llm_calls{component=composer}")
    void composerLlmCallMetric() throws Exception {
        when(chatClient.chat(any(), anyList())).thenReturn("答案");
        DefaultEvidenceGroundedAnswerComposer composer =
                new DefaultEvidenceGroundedAnswerComposer(chatClient);
        composer.setMetricsPort(metrics);
        EvidenceGroundedAnswerComposer.GroundedAnswerRequest req =
                new EvidenceGroundedAnswerComposer.GroundedAnswerRequest(
                        "q", List.of(), List.of(), List.of(), "tA", "r-1");
        var answer = composer.compose(req);
        assertThat(answer.text()).isEqualTo("答案");
        verify(metrics).recordAgentLlmCall("composer");
    }
}
