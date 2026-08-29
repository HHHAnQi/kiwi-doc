package com.xxx.ragdoc.application.chat.sufficiency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P1-B: sufficiency 指标接线单测 — ragdoc.agent.sufficiency_total 在 DispatchingSufficiencyJudge
 * 单一出口记录(每判定恰一笔); ragdoc.agent.llm_calls_total{component=sufficiency} 在 ModelSufficiencyJudge
 * 真实调用点记录。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P1-B Agent指标接线 - sufficiency 权威点")
class SufficiencyMetricsWiringTest {

    @Mock private MetricsPort metrics;
    @Mock private RuleSufficiencyJudge ruleJudge;
    @Mock private ModelSufficiencyJudge modelJudge;
    @Mock private ChatClient chatClient;

    private SufficiencyProperties props;
    private DispatchingSufficiencyJudge dispatcher;

    private final SufficiencyRequest anyRequest =
            new SufficiencyRequest(
                    "r1",
                    "q",
                    List.of(),
                    List.of(),
                    Set.of(),
                    Set.of(),
                    EvidenceCoverageSummary.empty(),
                    0,
                    true,
                    Map.of());

    @BeforeEach
    void setup() {
        props = new SufficiencyProperties();
        props.setEnabled(true);
        props.setModelFallbackEnabled(true);
        dispatcher = new DispatchingSufficiencyJudge(ruleJudge, modelJudge, props);
        dispatcher.setMetricsPort(metrics);
    }

    @Test
    @DisplayName("Rule 判 SUFFICIENT → sufficiency_total{SUFFICIENT} 恰一次")
    void sufficiencyMetricAtDispatchExit() {
        when(ruleJudge.evaluate(any()))
                .thenReturn(
                        SufficiencyDecision.rule(
                                SufficiencyStatus.SUFFICIENT,
                                List.of(RequirementCoverage.covered("R1", List.of("ev1"), "")),
                                List.of(),
                                List.of(),
                                RecommendedAction.ANSWER,
                                "OK"));
        dispatcher.evaluate(anyRequest);
        verify(metrics).recordAgentSufficiency("SUFFICIENT");
    }

    @Test
    @DisplayName("disabled → UNDETERMINED 也经同一出口记录(每判定恰一笔)")
    void sufficiencyMetricDisabledPath() {
        props.setEnabled(false);
        dispatcher.evaluate(anyRequest);
        verify(metrics).recordAgentSufficiency("UNDETERMINED");
    }

    @Test
    @DisplayName("ModelSufficiencyJudge 真实 LLM 调用点 → llm_calls{component=sufficiency}(调用失败也计)")
    void modelJudgeLlmCallMetric() throws Exception {
        ModelSufficiencyJudge judge =
                new ModelSufficiencyJudge(chatClient, new ObjectMapper(), props);
        judge.setMetricsPort(metrics);
        when(chatClient.chat(any(), any())).thenThrow(new RuntimeException("llm down"));
        SufficiencyDecision d = judge.evaluate(anyRequest); // LLM 失败 → 保守 UNDETERMINED
        verify(metrics).recordAgentLlmCall("sufficiency");
        org.assertj.core.api.Assertions.assertThat(d.status())
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }
}
