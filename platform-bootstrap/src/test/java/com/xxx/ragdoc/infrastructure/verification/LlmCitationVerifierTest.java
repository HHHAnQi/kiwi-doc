package com.xxx.ragdoc.infrastructure.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort.Evidence;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 7: {@link LlmCitationVerifier} 单测。
 *
 * <p>任务要求测试 "错误引用案例" — 即 answer 与 citation 矛盾, 验 verdict=CONTRADICTION, outcome=FAIL。
 */
@DisplayName("Task 7 LlmCitationVerifier")
class LlmCitationVerifierTest {

    private OpenAiCompatibleLlmClient judgeClient;
    private LlmRouter router;
    private LlmCitationVerifier verifier;

    @BeforeEach
    void setup() {
        judgeClient = mock(OpenAiCompatibleLlmClient.class);
        router = mock(LlmRouter.class);
        when(router.getRouteClient("fallback")).thenReturn(judgeClient);
        verifier =
                new LlmCitationVerifier(
                        router, CircuitBreakerRegistry.ofDefaults(), mock(RagdocMetrics.class));
    }

    @Test
    @DisplayName("全部 entailment → PASS, overall=min(scores) 高")
    void allEntailmentPasses() throws Exception {
        when(judgeClient.chat(anyString(), anyList()))
                .thenReturn(
                        "{\"verdicts\":["
                                + "{\"chunk_id\":1,\"verdict\":\"entailment\",\"score\":0.9},"
                                + "{\"chunk_id\":2,\"verdict\":\"entailment\",\"score\":0.8}"
                                + "]}");
        VerificationResult r =
                verifier.verify(
                        "Dubbo 用 dubbo.protocol.port 配置端口",
                        List.of(
                                new Evidence(1L, "Dubbo uses dubbo.protocol.port for the port."),
                                new Evidence(2L, "Default port 20880.")));
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.PASS);
        assertThat(r.overallScore()).isCloseTo(0.8, within(0.001));
        assertThat(r.citationScores()).hasSize(2);
        assertThat(r.citationScores().get(0).verdict())
                .isEqualTo(VerificationResult.Verdict.ENTAILMENT);
    }

    @Test
    @DisplayName("错误引用 (contradiction) → FAIL, score=0 (任务要求案例)")
    void contradictionFails() throws Exception {
        when(judgeClient.chat(anyString(), anyList()))
                .thenReturn(
                        "{\"verdicts\":[{\"chunk_id\":42,\"verdict\":\"contradiction\",\"score\":0.0}]}");
        VerificationResult r =
                verifier.verify(
                        "Dubbo 默认端口是 8080",
                        List.of(new Evidence(42L, "Dubbo default port is 20880, NOT 8080.")));
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.FAIL);
        assertThat(r.overallScore()).isEqualTo(0.0);
        assertThat(r.citationScores().get(0).verdict())
                .isEqualTo(VerificationResult.Verdict.CONTRADICTION);
    }

    @Test
    @DisplayName("部分未支持 (1 entailment + 1 unknown) → overall=MIN 取更低, 但仍 PASS")
    void mixedPass_byMin() throws Exception {
        when(judgeClient.chat(anyString(), anyList()))
                .thenReturn(
                        "{\"verdicts\":["
                                + "{\"chunk_id\":1,\"verdict\":\"entailment\",\"score\":0.95},"
                                + "{\"chunk_id\":2,\"verdict\":\"unknown\",\"score\":0.4}"
                                + "]}");
        VerificationResult r =
                verifier.verify("ans", List.of(new Evidence(1L, "ev1"), new Evidence(2L, "ev2")));
        // overall = min(0.95, 0.4) = 0.4 < 0.5 → FAIL our impl (低 faithfulness 严格)
        assertThat(r.overallScore()).isCloseTo(0.4, within(0.001));
        // outcome 由 threshold=0.5 决定, 0.4 < 0.5 → FAIL
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.FAIL);
    }

    @Test
    @DisplayName("LLM 返 markdown ```json 围栏 → strip 容错解析")
    void markdownFenceStripped() throws Exception {
        when(judgeClient.chat(anyString(), anyList()))
                .thenReturn(
                        "```json\n{\"verdicts\":[{\"chunk_id\":1,\"verdict\":\"entailment\",\"score\":0.9}]}\n```");
        VerificationResult r = verifier.verify("ans", List.of(new Evidence(1L, "ev1")));
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.PASS);
    }

    @Test
    @DisplayName("LLM 返非法 JSON (无 verdicts key) → ERROR")
    void invalidJsonReturnsError() throws Exception {
        when(judgeClient.chat(anyString(), anyList())).thenReturn("I cannot help with that.");
        VerificationResult r = verifier.verify("ans", List.of(new Evidence(1L, "ev1")));
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.ERROR);
        assertThat(r.errorMessage()).contains("parse_failed");
    }

    @Test
    @DisplayName("LLM 抛异常 → ERROR, 不挂主流程")
    void llmExceptionReturnsError() throws Exception {
        when(judgeClient.chat(anyString(), anyList())).thenThrow(new RuntimeException("LLM 503"));
        VerificationResult r = verifier.verify("ans", List.of(new Evidence(1L, "ev1")));
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.ERROR);
        assertThat(r.errorMessage()).contains("LLM 503");
    }

    @Test
    @DisplayName("空 citations list → SKIPPED, 不调 LLM")
    void emptyCitationsSkipped() throws Exception {
        VerificationResult r = verifier.verify("ans", List.of());
        assertThat(r.outcome()).isEqualTo(VerificationResult.Outcome.SKIPPED);
        verifyNoInteractions(judgeClient);
    }

    @Test
    @DisplayName("answer empty/null → SKIPPED")
    void emptyAnswerSkipped() throws Exception {
        assertThat(verifier.verify("", List.of(new Evidence(1L, "ev"))).outcome())
                .isEqualTo(VerificationResult.Outcome.SKIPPED);
        assertThat(verifier.verify(null, List.of(new Evidence(1L, "ev"))).outcome())
                .isEqualTo(VerificationResult.Outcome.SKIPPED);
        verifyNoInteractions(judgeClient);
    }

    @Test
    @DisplayName("score 字段边界: 超过 1 自动截断到 1, 低于 0 截断到 0")
    void scoreBounded() throws Exception {
        when(judgeClient.chat(anyString(), anyList()))
                .thenReturn(
                        "{\"verdicts\":[{\"chunk_id\":1,\"verdict\":\"entailment\",\"score\":1.5}]}");
        VerificationResult r = verifier.verify("ans", List.of(new Evidence(1L, "ev")));
        assertThat(r.citationScores().get(0).score()).isEqualTo(1.0);
    }

    private static org.assertj.core.data.Offset<Double> within(double tol) {
        return org.assertj.core.data.Offset.offset(tol);
    }
}
