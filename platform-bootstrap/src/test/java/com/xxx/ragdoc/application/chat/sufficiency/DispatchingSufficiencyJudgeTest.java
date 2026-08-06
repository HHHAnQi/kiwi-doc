package com.xxx.ragdoc.application.chat.sufficiency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7b: {@link DispatchingSufficiencyJudge} — Rule 优先 + Model fallback 路由。 */
@DisplayName("DispatchingSufficiencyJudge - PR-7b Rule 优先 + Model fallback")
class DispatchingSufficiencyJudgeTest {

    private RuleSufficiencyJudge ruleJudge;
    private ModelSufficiencyJudge modelJudge;
    private SufficiencyProperties props;
    private DispatchingSufficiencyJudge dispatcher;

    private final SufficiencyRequest anyRequest = new SufficiencyRequest(
            "r1", "q", List.of(), List.of(), Set.of(), Set.of(),
            EvidenceCoverageSummary.empty(), 0, true, Map.of());

    @BeforeEach
    void setup() {
        ruleJudge = mock(RuleSufficiencyJudge.class);
        modelJudge = mock(ModelSufficiencyJudge.class);
        props = new SufficiencyProperties();
        dispatcher = new DispatchingSufficiencyJudge(ruleJudge, modelJudge, props);
    }

    @Test
    @DisplayName("props.enabled=false → 直接 UNDETERMINED (SUFFICIENCY_DISABLED), 不调任何 Judge")
    void disabled() {
        props.setEnabled(false);
        SufficiencyDecision d = dispatcher.evaluate(anyRequest);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.reasonCode()).isEqualTo("SUFFICIENCY_DISABLED");
        verify(ruleJudge, never()).evaluate(any());
        verify(modelJudge, never()).evaluate(any());
    }

    @Test
    @DisplayName("Rule 返回 SUFFICIENT → 直接返回, 不调 Model")
    void ruleSufficient() {
        props.setEnabled(true);
        when(ruleJudge.evaluate(any())).thenReturn(SufficiencyDecision.rule(
                SufficiencyStatus.SUFFICIENT, List.of(), List.of(), List.of(),
                RecommendedAction.ANSWER, "RULE"));
        SufficiencyDecision d = dispatcher.evaluate(anyRequest);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        verify(modelJudge, never()).evaluate(any());
    }

    @Test
    @DisplayName("Rule UNDETERMINED + model-fallback=true → 调 Model")
    void ruleUndeterminedWithModelFallback() {
        props.setEnabled(true);
        props.setModelFallbackEnabled(true);
        when(ruleJudge.evaluate(any())).thenReturn(SufficiencyDecision.rule(
                SufficiencyStatus.UNDETERMINED, List.of(), List.of(), List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE, "RULE_SEMANTIC_UNDETERMINED"));
        when(modelJudge.evaluate(any())).thenReturn(SufficiencyDecision.model(
                SufficiencyStatus.SUFFICIENT, List.of(), List.of(), List.of(),
                RecommendedAction.ANSWER, "MODEL_SUFFICIENT"));

        SufficiencyDecision d = dispatcher.evaluate(anyRequest);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        assertThat(d.source()).isEqualTo("MODEL");
        verify(modelJudge, times(1)).evaluate(any());
    }

    @Test
    @DisplayName("Rule UNDETERMINED + model-fallback=false → 保持 UNDETERMINED, 不调 Model")
    void ruleUndeterminedNoFallback() {
        props.setEnabled(true);
        props.setModelFallbackEnabled(false);
        when(ruleJudge.evaluate(any())).thenReturn(SufficiencyDecision.rule(
                SufficiencyStatus.UNDETERMINED, List.of(), List.of(), List.of(),
                RecommendedAction.REFUSE_NO_EVIDENCE, "RULE_SEMANTIC_UNDETERMINED"));

        SufficiencyDecision d = dispatcher.evaluate(anyRequest);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        verify(modelJudge, never()).evaluate(any());
    }
}
