package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.agent.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 确定性上下文预算器：保序、不可变、最后一段按 token 预算安全截断。 */
@Component
public class TokenBudgetContextBuilder {

    /**
     * P0 修复: build 结果额外带 keptCount(实际保留的 entry 数), 调用方须把 citations 截到该数 —
     * 否则 LLM 只看到前 k 条 evidence, 但响应仍带全量 citations, 前端 [n] 编号与引用卡片错位。
     */
    public BuildResult build(List<String> candidates, int tokenBudget) {
        return build(candidates, tokenBudget, Integer.MAX_VALUE);
    }

    /**
     * 双闸门版本: token 预算 + 总字符上限。
     *
     * <p>maxTotalChars 与 OpenAiCompatibleLlmClient.max-context-chars 对齐(留出编号开销余量后传入):
     * 若只在 token 维度截断, chars 可能仍超 LLM client 的内层 cap, client 会再次 tail-drop —
     * 那次截断对 ChatService 不可见, citations 对齐又被打破。在此一次算清, client 侧 cap 成为 no-op。
     */
    public BuildResult build(List<String> candidates, int tokenBudget, int maxTotalChars) {
        if (tokenBudget <= 0) throw new IllegalArgumentException("tokenBudget 必须大于 0");
        List<String> input = candidates == null ? List.of() : candidates;
        List<String> kept = new ArrayList<>();
        int used = 0;
        int usedChars = 0;
        boolean truncated = false;
        for (String candidate : input) {
            String text = candidate == null ? "" : candidate.trim();
            if (text.isEmpty()) continue;
            int tokens = TokenEstimator.estimate(text);
            boolean fitsTokens = used + tokens <= tokenBudget;
            boolean fitsChars = usedChars + text.length() <= maxTotalChars;
            if (fitsTokens && fitsChars) {
                kept.add(text);
                used += tokens;
                usedChars += text.length();
                continue;
            }
            int remainingTokens = tokenBudget - used;
            int remainingChars = maxTotalChars - usedChars;
            if (remainingTokens > 0 && remainingChars > 0) {
                String prefix = largestPrefixWithin(text, remainingTokens, remainingChars);
                if (!prefix.isBlank()) {
                    kept.add(prefix);
                    used += TokenEstimator.estimate(prefix);
                    usedChars += prefix.length();
                }
            }
            truncated = true;
            break;
        }
        if (kept.size() < input.stream().filter(s -> s != null && !s.isBlank()).count()) truncated = true;
        return new BuildResult(List.copyOf(kept), used, tokenBudget, truncated);
    }

    /**
     * 双约束下的最长前缀: 长度 ≤ charBudget 且 token 估算 ≤ tokenBudget。
     * (前缀若只按 token 估, ASCII 文本 token 密度低时前缀会长于字符上限, char 闸门被绕过。)
     */
    private static String largestPrefixWithin(String text, int tokenBudget, int charBudget) {
        int maxLen = Math.min(text.length(), Math.max(0, charBudget));
        int low = 0;
        int high = maxLen;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimator.estimate(text.substring(0, mid)) <= tokenBudget) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low).stripTrailing();
    }

    public record BuildResult(List<String> context, int estimatedTokens, int tokenBudget, boolean truncated) {}
}
