package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.conversation.ContextualizeResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 多轮对话 query 重写器, ADR-0011 §4。
 *
 * <p>LlamaIndex {@code condense_question} 流派: 输入 currQuery + 最近 N turn history, 让 fallback LLM 改写成
 * standalone query (无指代), 喂 retrieve。LLM 失败回退原 query, 用户不感知。
 *
 * <h3>关键决策 (见 ADR-0011 §4 §9.4)</h3>
 *
 * <ul>
 *   <li>用 fallback LLM (DeepSeek-V3 便宜) 跑 rewrite, 不浪费主 route 的 GLM-4-plus token
 *   <li>单独 CircuitBreaker instance {@code "rewrite-llm"}, 不与主 LLM 共用 cb pool (防 rewrite 慢拖垮主对话)
 *   <li>鹦鹉学舌检测: rewrite == 原 query → 视为 skip, 节省后续 retrieve 跑偏 (LLM 偶尔复读原 prompt)
 *   <li>失败回退: 任何异常都返回 {@link ContextualizeResult#failed}, ChatService 用 {@link
 *       ContextualizeResult#retrieveQuery} 拿原 query, 不挂 chat
 * </ul>
 *
 * <p>{@code @ConditionalOnProperty} 让此 Bean 仅在 conversation.enabled=true 时启用, false 时根本不注入,
 * ChatService 拿到的是 null (用 Optional 而非 null 更体面, 但 ChatService 实现简化用 null check)。
 *
 * @author Phase 1 / C3 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.conversation", name = "enabled", havingValue = "true")
public class QueryContextualizer implements QueryContextualizerPort {

    /**
     * 取 history 最近 N turn 喂 rewrite LLM, 控制 prompt input token (~500)。 不用 rollingSummary: 是压缩过的, 喂
     * rewrite LLM 反而扰指代消解。
     */
    private static final int HISTORY_TURNS_FED = 3;

    private static final String CONDENSE_PROMPT_TEMPLATE =
            """
            你是多轮对话上下文压缩助手。基于以下对话历史和后续问题, 把后续问题改写成一个自包含、
            无指代的独立问题 (resolve pronouns, e.g. 他/它/刚才 → 具体实体名)。

            对话历史:
            %s

            后续问题: %s

            要求:
            1. 输出 1 句中文, 不超过 60 字
            2. 保留后续问题的核心实体
            3. 不要回答问题, 只改写
            4. 不要任何前缀、引号、解释, 直接输出改写后的独立问题

            独立问题: """;

    private final ChatClient rewriteClient;
    private final CircuitBreaker cb;
    private final RagdocMetrics metrics;

    public QueryContextualizer(
            LlmRouter llmRouter, CircuitBreakerRegistry cbRegistry, RagdocMetrics metrics) {
        // 走 fallback LLM (DeepSeek-V3 便宜); LlmRouter 没 fallback 时退到 primary (rare, rare)
        this.rewriteClient = llmRouter.getRouteClient("fallback");
        // 单独 cb instance "rewrite-llm", 不与主 LLM 共用 cb pool
        this.cb = cbRegistry.circuitBreaker("rewrite-llm");
        this.metrics = metrics;
        log.info(
                "QueryContextualizer enabled, route=fallback, cb=rewrite-llm (state={})",
                cb.getState());
    }

    /**
     * 把 currQuery 用 history 改写为 standalone query。
     *
     * @param currQuery 当前 turn 用户原始输入
     * @param recentTurns ConversationContext.recentTurns(), may be empty
     * @return 三类 outcome (skip / ok / failed); 调用方调 {@link ContextualizeResult#retrieveQuery} 拿实际跑去
     *     retrieve 的 query
     */
    public ContextualizeResult contextualize(String currQuery, List<Turn> recentTurns) {
        long t0 = System.currentTimeMillis();

        if (recentTurns == null || recentTurns.isEmpty()) {
            // 第 1 turn 不用 rewrite, 避免浪费 LLM call
            // durationMs 仍 sync t0 取, 让 metric 与其他 path 同分桶 (空 history 的 latency 应接近 0,
            // 与 LLM 跳过路径互相对照)
            long elapsed = System.currentTimeMillis() - t0;
            metrics.recordRewriteLatency(elapsed, "skip");
            return ContextualizeResult.skipped(currQuery, elapsed);
        }

        String prompt = buildPrompt(currQuery, recentTurns);
        try {
            String rewritten =
                    cb.executeSupplier(
                            () -> {
                                try {
                                    return rewriteClient.chat(prompt, List.of());
                                } catch (Exception e) {
                                    // executeSupplier 内 lambda 只能抛 RuntimeException
                                    throw new RuntimeException(e);
                                }
                            });

            long elapsed = System.currentTimeMillis() - t0;

            // Quality gate: 鹦鹉学舌检测 — rewrite 等于 / 几乎等于原 query 视为 skip
            if (isParroted(currQuery, rewritten)) {
                metrics.recordRewriteLatency(elapsed, "skip");
                return ContextualizeResult.skipped(currQuery, elapsed);
            }

            metrics.recordRewriteLatency(elapsed, "ok");
            return ContextualizeResult.success(currQuery, trimmed(rewritten), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.warn(
                    "rewrite.failed fallback to original, query_len={}, reason={}",
                    currQuery.length(),
                    rootCause(e));
            metrics.recordRewriteLatency(elapsed, "failed");
            return ContextualizeResult.failed(currQuery, elapsed);
        }
    }

    private String buildPrompt(String currQuery, List<Turn> recentTurns) {
        int n = Math.min(HISTORY_TURNS_FED, recentTurns.size());
        // 取最近 N turn (老的丢 — 它们在 rollingSummary 里如果有, 但不喂 rewrite)
        List<Turn> last3 = recentTurns.subList(recentTurns.size() - n, recentTurns.size());

        StringBuilder sb = new StringBuilder();
        for (Turn t : last3) {
            sb.append("Q: ").append(t.userQuery()).append('\n');
            // botAnswer 截断 100 字防 prompt 膨胀 (rewrite 用, 不需要完整答案)
            sb.append("A: ").append(truncate(t.botAnswer(), 100)).append('\n');
        }
        return String.format(CONDENSE_PROMPT_TEMPLATE, sb.toString().trim(), currQuery);
    }

    /**
     * 鹦鹉学舌检测 — LLM 偶尔直接复读原 query, 此时 rewrite 无意义, retrieve 跑偏。
     *
     * <p>P0 修复: 原实现的双向 {@code contains} 会把一切合法的指代消解改写误判为鹦鹉 —
     * condense 式改写天然以原问为子串("那它支持哪些？"→"Dubbo 支持哪些序列化？"含原问全部字符),
     * 实测多轮 G2 gate 仅 2/20 通过。现收紧为:
     *
     * <ul>
     *   <li>完全相等(去空白/大小写) → 鹦鹉
     *   <li>改写只比原问多/少标点或前后缀语气词(编辑相似度 ≥ 0.9) → 鹦鹉
     *   <li>其余(含原问子串但新增了实质内容) → 有效改写
     * </ul>
     */
    private static boolean isParroted(String original, String rewritten) {
        if (rewritten == null) return true;
        String o = normalized(original);
        String r = normalized(rewritten);
        if (o.isEmpty() || r.isEmpty()) return true;
        if (o.equals(r)) return true;
        // 改写长度与原问相差悬殊 → 一定增/删了实质内容, 不是复读
        double lenRatio = Math.min(o.length(), r.length()) / (double) Math.max(o.length(), r.length());
        if (lenRatio < 0.9) return false;
        // 长度接近时用 Levenshtein 相似度: ≥0.9 视为只动了标点/语气词的复读
        return similarity(o, r) >= 0.9;
    }

    /** 归一化编辑相似度 1 - lev(a,b)/max(len)。 */
    private static double similarity(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 && m == 0) return 1.0;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return 1.0 - prev[m] / (double) Math.max(n, m);
    }

    private static String normalized(String s) {
        // 鹦鹉判定用归一化: 去空白 + 去标点(中英文) + 小写 — 只差标点的复读不算改写
        return s == null
                ? ""
                : s.replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "")
                        .toLowerCase();
    }

    private static String trimmed(String s) {
        // LLM 可能在开头加换行 / 空格 / 中英文引号, 全去
        // 用 Unicode escape 避开源码字符 escaping hell: " = \u201c, " = \u201d, " = \u0022
        return s.replaceAll("^[\\s\\u0022\\u201c\\u201d']+", "")
                .replaceAll("[\\s\\u0022\\u201c\\u201d']+$", "")
                .trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
