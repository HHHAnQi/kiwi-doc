package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.agent.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 确定性上下文预算器：保序、不可变、最后一段按 token 预算安全截断。 */
@Component
public class TokenBudgetContextBuilder {

    public BuildResult build(List<String> candidates, int tokenBudget) {
        if (tokenBudget <= 0) throw new IllegalArgumentException("tokenBudget 必须大于 0");
        List<String> input = candidates == null ? List.of() : candidates;
        List<String> kept = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (String candidate : input) {
            String text = candidate == null ? "" : candidate.trim();
            if (text.isEmpty()) continue;
            int tokens = TokenEstimator.estimate(text);
            if (used + tokens <= tokenBudget) {
                kept.add(text);
                used += tokens;
                continue;
            }
            int remaining = tokenBudget - used;
            if (remaining > 0) {
                String prefix = largestPrefixWithin(text, remaining);
                if (!prefix.isBlank()) {
                    kept.add(prefix);
                    used += TokenEstimator.estimate(prefix);
                }
            }
            truncated = true;
            break;
        }
        if (kept.size() < input.stream().filter(s -> s != null && !s.isBlank()).count()) truncated = true;
        return new BuildResult(List.copyOf(kept), used, tokenBudget, truncated);
    }

    private static String largestPrefixWithin(String text, int budget) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimator.estimate(text.substring(0, mid)) <= budget) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low).stripTrailing();
    }

    public record BuildResult(List<String> context, int estimatedTokens, int tokenBudget, boolean truncated) {}
}
