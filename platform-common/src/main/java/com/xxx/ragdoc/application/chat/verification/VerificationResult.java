package com.xxx.ragdoc.application.chat.verification;

import java.util.List;

/**
 * Task 7: Citation Verification 返回值 — NLI verdict + 数值化 score。
 *
 * <p>NLI (Natural Language Inference) 三态:
 *
 * <ul>
 *   <li>{@code ENTAILMENT}: answer 可由 citation 推出 (score 高, 通常 0.7-1.0)
 *   <li>{@code CONTRADICTION}: answer 与 citation 矛盾 (score=0)
 *   <li>{@code UNKNOWN}: citation 不含足够信息判 (score 中等, ~0.3-0.5)
 * </ul>
 *
 * <p>{@link Outcome} 是整体判定:
 *
 * <ul>
 *   <li>{@code PASS}: 任一 citation ENTAILMENT, overall-score ≥ threshold
 *   <li>{@code FAIL}: 全部 citation 失支持 (CONTRADICTION / UNKNOWN/低 score)
 *   <li>{@code SKIPPED}: citations 空 / verifier 内部决定不验 (e.g. 答 case-by-case)
 *   <li>{@code ERROR}: LLM/网络异常, 上游决定怎么处理 (WARN_ONLY 时仍 OK)
 * </ul>
 */
public record VerificationResult(
        Outcome outcome,
        double overallScore,
        List<CitationScore> citationScores,
        String errorMessage) {

    public VerificationResult {
        if (citationScores == null) citationScores = List.of();
    }

    public enum Outcome {
        PASS,
        FAIL,
        SKIPPED,
        ERROR
    }

    /** 空用例: SKIPPED, 不调 LLM (citations 空)。 */
    public static VerificationResult skipped() {
        return new VerificationResult(Outcome.SKIPPED, 0.0, List.of(), null);
    }

    /** ERROR 用例: LLM/网络/JSON 解析异常, 上游决定处理。 */
    public static VerificationResult error(String errorMessage) {
        return new VerificationResult(Outcome.ERROR, 0.0, List.of(), errorMessage);
    }

    /** 单 citation NLI 结果。 */
    public record CitationScore(long chunkId, Verdict verdict, double score) {

        public CitationScore {
            if (score < 0.0) score = 0.0;
            if (score > 1.0) score = 1.0;
        }
    }

    /** NLI 三态对应任务文档的 entailment / contradiction / unknown。 */
    public enum Verdict {
        ENTAILMENT,
        CONTRADICTION,
        UNKNOWN
    }
}
