package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import java.util.List;

/**
 * PR-4 / EMS-PR4: citation_verify Tool 的结构化输出。
 *
 * <p>透传 {@link VerificationResult} 的关键维度, 但把 {@code errorMessage} 等可能含 LLM 内部信息的字段做安全清洗; Agent /
 * Planner 拿到的是 outcome / score / verdict (与 ChatResult.verification 同型)。
 */
public record CitationVerifyOutput(
        String outcome, // PASS / FAIL / SKIPPED / ERROR
        double overallScore,
        List<CitationVerifyOutput.CitationScore> citationScores,
        boolean skipped // enabled=false 时为 true, 让 Planner 知道不要把分当真
        ) implements ToolOutput {

    public CitationVerifyOutput {
        citationScores = citationScores == null ? List.of() : List.copyOf(citationScores);
        outcome = outcome == null ? "SKIPPED" : outcome;
    }

    public record CitationScore(long chunkId, String verdict, double score) {}

    public static CitationVerifyOutput from(VerificationResult r) {
        List<CitationScore> scores =
                r.citationScores() == null
                        ? List.of()
                        : r.citationScores().stream()
                                .map(
                                        cs ->
                                                new CitationScore(
                                                        cs.chunkId(),
                                                        cs.verdict() == null
                                                                ? null
                                                                : cs.verdict().name(),
                                                        cs.score()))
                                .toList();
        return new CitationVerifyOutput(
                r.outcome() == null ? null : r.outcome().name(),
                r.overallScore(),
                scores,
                r.outcome() == VerificationResult.Outcome.SKIPPED);
    }
}
