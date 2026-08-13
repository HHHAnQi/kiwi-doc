package com.xxx.ragdoc.infrastructure.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import com.xxx.ragdoc.application.chat.verification.VerificationResult.CitationScore;
import com.xxx.ragdoc.application.chat.verification.VerificationResult.Outcome;
import com.xxx.ragdoc.application.chat.verification.VerificationResult.Verdict;
import com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Task 7: {@link CitationVerifierPort} LLM 实现。
 *
 * <p>调 {@code LlmRouter.getRouteClient("fallback")} 走便宜 LLM 做 NLI judge, 输出严格 JSON {@code
 * [{chunk_id, verdict, score}, ...]}。失败/超时/熔断/解析错返 {@link VerificationResult#error}, 不挂 chat 主流程。
 *
 * <p>设计决策 (mirror ADR-0011 §4 + Tasks 5/6):
 *
 * <ul>
 *   <li>走 fallback route (DeepSeek-V3 便宜), 不浪费 primary GLM-4-plus token 做 NLI judge
 *   <li>独立 CircuitBreaker {@code "citation-verifier-llm"}, 隔离主 chat + rewrite-llm +
 *       query-enhance-llm
 *   <li>整体 score = MIN(citation scores), 与"任一 citation 未支持就 FAIL"对齐 (faithfulness 严格)
 *   <li>Prompt 改自 {@code eval/metrics/generation_metrics.py:108-115} faithfulness 范式
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.citation-verifier", name = "enabled", havingValue = "true")
public class LlmCitationVerifier implements CitationVerifierPort {

    private static final String CB_NAME = "citation-verifier-llm";

    private final OpenAiCompatibleLlmClient judgeClient;
    private final CircuitBreaker cb;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** NLI 判定 prompt (改自 faithfulness 范式, 与 Python eval 同源)。 */
    private static final String NLI_PROMPT_TEMPLATE =
            """
            你是严谨的自然语言推理 (NLI) judge。基于下方"候选答案"和"证据文本", 判定答案是否被证据支持。

            候选答案:
            %s

            证据文本 (citation_id: 内容):
            %s

            要求:
            1. 对每条证据独立判定 verdict ∈ {entailment, contradiction, unknown}
               - entailment: 答案完全或部分可由证据推出 (score 高, 0.6-1.0)
               - contradiction: 答案与证据矛盾 (score=0.0)
               - unknown: 证据无法判定 (score 中等, 0.3-0.5)
            2. score ∈ [0, 1] 浮点, 反映支持程度
            3. 输出严格 JSON, 不要 markdown 围栏, 不要任何前缀解释

            输出格式:
            {"verdicts": [{"chunk_id": <number>, "verdict": "<entailment|contradiction|unknown>", "score": <float>}, ...]}

            判定: """;

    public LlmCitationVerifier(
            LlmRouter router, CircuitBreakerRegistry cbRegistry, RagdocMetrics metrics) {
        this.judgeClient = router.getRouteClient("fallback");
        this.cb = cbRegistry.circuitBreaker(CB_NAME);
        log.info(
                "LlmCitationVerifier enabled, route=fallback, cb={} (state={})",
                CB_NAME,
                cb.getState());
    }

    @Override
    public VerificationResult verify(String answer, List<Evidence> evidences) {
        if (answer == null || answer.isBlank()) {
            return VerificationResult.skipped();
        }
        if (evidences == null || evidences.isEmpty()) {
            return VerificationResult.skipped();
        }
        long t0 = System.currentTimeMillis();
        String prompt = buildPrompt(answer, evidences);
        try {
            String raw =
                    cb.executeSupplier(
                            () -> {
                                try {
                                    return judgeClient.chat(prompt, List.of());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            long elapsed = System.currentTimeMillis() - t0;
            return parseAndAggregate(answer, evidences, raw, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            String reason = rootCause(e);
            log.warn(
                    "citation_verify.failed fallback=ERROR, answer_len={}, n_evidences={}, reason={}",
                    answer.length(),
                    evidences.size(),
                    reason);
            return VerificationResult.error(reason + " (elapsed=" + elapsed + "ms)");
        }
    }

    private String buildPrompt(String answer, List<Evidence> evidences) {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : evidences) {
            sb.append("citation_id ")
                    .append(e.chunkId())
                    .append(": ")
                    .append(truncate(e.text(), 500))
                    .append('\n');
        }
        return String.format(NLI_PROMPT_TEMPLATE, truncate(answer, 1000), sb.toString().trim());
    }

    private VerificationResult parseAndAggregate(
            String answer, List<Evidence> evidences, String raw, long elapsed) {
        // strip markdown 围栏
        if (raw == null || raw.indexOf('{') < 0 || raw.lastIndexOf('}') <= raw.indexOf('{')) {
            log.warn(
                    "citation_verify.parse_failed no_json_in_response, raw_head={}",
                    raw == null ? "" : raw.substring(0, Math.min(80, raw.length())));
            return VerificationResult.error(
                    "parse_failed: no JSON in response (elapsed=" + elapsed + "ms)");
        }
        String jsonStr = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode arr = root.get("verdicts");
            if (arr == null || !arr.isArray()) {
                log.warn(
                        "citation_verify.parse_failed verdicts_not_array, raw_head={}",
                        raw.substring(0, Math.min(80, raw.length())));
                return VerificationResult.error(
                        "parse_failed: verdicts not array (elapsed=" + elapsed + "ms)");
            }
            List<CitationScore> scores = new ArrayList<>();
            for (JsonNode v : arr) {
                long cid = v.path("chunk_id").asLong(0L);
                Verdict verdict = parseVerdict(v.path("verdict").asText(""));
                double score = v.path("score").asDouble(verdict == Verdict.ENTAILMENT ? 0.5 : 0.0);
                if (cid > 0) {
                    scores.add(new CitationScore(cid, verdict, score));
                }
            }
            if (scores.isEmpty()) {
                return VerificationResult.error(
                        "parse_failed: no valid verdict entries (elapsed=" + elapsed + "ms)");
            }
            // 整体 score = MIN(citation scores): 任一未支持就 FAIL (faithfulness 严格)
            double overall = scores.stream().mapToDouble(CitationScore::score).min().orElse(0.0);
            Outcome outcome = overall >= 0.5 ? Outcome.PASS : Outcome.FAIL;
            log.info(
                    "citation_verify.ok answer_len={}, n_evidences={}, n_verdicts={}, min_score={}, outcome={}",
                    answer.length(),
                    evidences.size(),
                    scores.size(),
                    String.format("%.3f", overall),
                    outcome);
            return new VerificationResult(outcome, overall, scores, null);
        } catch (Exception parseEx) {
            log.warn(
                    "citation_verify.parse_failed err={}, raw_head={}",
                    parseEx.getMessage(),
                    raw.substring(0, Math.min(80, raw.length())));
            return VerificationResult.error(
                    "parse_failed: " + parseEx.getMessage() + " (elapsed=" + elapsed + "ms)");
        }
    }

    private static Verdict parseVerdict(String raw) {
        if (raw == null) return Verdict.UNKNOWN;
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "entailment", "yes", "support" -> Verdict.ENTAILMENT;
            case "contradiction", "no", "refute" -> Verdict.CONTRADICTION;
            default -> Verdict.UNKNOWN;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
