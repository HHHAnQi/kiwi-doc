package com.xxx.ragdoc.infrastructure.queryenhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.QueryEnhanceProperties;
import com.xxx.ragdoc.application.chat.conversation.EnhanceResult;
import com.xxx.ragdoc.application.chat.conversation.port.QueryProcessorPort;
import com.xxx.ragdoc.domain.auth.Principal;
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
 * Task 6: 单轮 query 增强 (rewrite + expansion) 适配器。
 *
 * <p>与 {@link com.xxx.ragdoc.infrastructure.conversation.QueryContextualizer} 并存但不冲突:
 *
 * <ul>
 *   <li>Contextualizer: 多轮代词解析 (依赖 history), 走 cb pool {@code "rewrite-llm"}
 *   <li>QueryProcessor: 单轮术语规范化 / 多元扩展 (不依赖 history), 走 cb pool {@code "query-enhance-llm"}
 * </ul>
 *
 * <p>关键决策 (mirror ADR-0011 §4 G3/G4 教训):
 *
 * <ul>
 *   <li>走 fallback LLM (DeepSeek-V3 便宜) — 不浪费 primary GLM-4-plus token
 *   <li>独立 CB instance {@code "query-enhance-llm"}, 不与 rewrite-llm / 主 chat cb pool 共用
 *   <li>parrot-echo 检测 (normalize 对比) → SKIP, 避免 LLM 复读或同义空转浪费后续 retrieve
 *   <li>失败 (LLM/网络/熔断/JSON 解析) → EnhanceResult.failed fallback 原 query, 不挂 chat 主流程
 * </ul>
 *
 * <p>{@code @ConditionalOnProperty} 让本 Bean 仅在 {@code rag.query-enhance.enabled=true} 时启用,
 * 关闭时根本不装配, ChatService 拿到 null。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.query-enhance", name = "enabled", havingValue = "true")
public class QueryProcessor implements QueryProcessorPort {

    /** cb instance 名 — 独立于 rewrite-llm (多轮) 与主 chat cb pool。 */
    private static final String CB_NAME = "query-enhance-llm";

    private final OpenAiCompatibleLlmClient enhanceClient;
    private final CircuitBreaker cb;
    private final RagdocMetrics metrics;
    private final QueryEnhanceProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String REWRITE_PROMPT_TEMPLATE =
            """
            你是查询改写助手。把下方用户查询改写为更利于检索的样子: 俚语 → 术语; 缩写 → 全称; 模糊词 → 具体名词。
            不要回答问题, 只改写, 输出一句话。

            用户查询: %s

            输出格式 (严格 JSON, 不要任何 markdown/前缀):
            {"rewritten": "...改写后的查询..."}

            改写后查询: """;

    private static final String EXPANSION_PROMPT_TEMPLATE =
            """
            你是查询扩展助手。基于下方用户查询, 生成 %d 个语义相关的不同表达, 用于多视角召回向量检索。
            每个扩展查询应保留核心意图, 但用不同的术语 / 切入角度。

            用户查询: %s

            输出格式 (严格 JSON, 不要任何 markdown/前缀):
            {"expansions": ["扩展查询1", "扩展查询2", ...]}

            扩展查询: """;

    private static final String BOTH_PROMPT_TEMPLATE =
            """
            你是查询增强助手。基于下方用户查询, 先改写为更规范的术语, 再生成 %d 个相关扩展查询。

            用户查询: %s

            输出格式 (严格 JSON, 不要任何 markdown/前缀):
            {"rewritten": "...改写主查询...", "expansions": ["...扩展1...", "...扩展2...", ...]}

            增强后: """;

    public QueryProcessor(
            LlmRouter router,
            CircuitBreakerRegistry cbRegistry,
            RagdocMetrics metrics,
            QueryEnhanceProperties props) {
        this.enhanceClient = router.getRouteClient("fallback");
        this.cb = cbRegistry.circuitBreaker(CB_NAME);
        this.metrics = metrics;
        this.props = props;
        log.info(
                "QueryProcessor enabled, mode={}, route=fallback, cb={} (state={})",
                props.getMode(),
                CB_NAME,
                cb.getState());
    }

    @Override
    public EnhanceResult enhance(String query, Principal principal) {
        long t0 = System.currentTimeMillis();
        if (query == null || query.isBlank()) {
            long elapsed = System.currentTimeMillis() - t0;
            return EnhanceResult.skipped(query == null ? "" : query, elapsed);
        }
        String prompt = buildPrompt(query);
        try {
            String raw =
                    cb.executeSupplier(
                            () -> {
                                try {
                                    return enhanceClient.chat(prompt, List.of());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            long elapsed = System.currentTimeMillis() - t0;
            return parseAndBuild(query, raw, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.warn(
                    "query_enhance.failed fallback=original, mode={}, query_len={}, reason={}",
                    props.getMode(),
                    query.length(),
                    rootCause(e));
            metrics.recordRewriteLatency(elapsed, "query_enhance_failed");
            return EnhanceResult.failed(query, rootCause(e), elapsed);
        }
    }

    private String buildPrompt(String query) {
        return switch (props.getMode()) {
            case REWRITE -> String.format(REWRITE_PROMPT_TEMPLATE, query);
            case EXPANSION ->
                    String.format(EXPANSION_PROMPT_TEMPLATE, props.getMaxExpansionQueries(), query);
            case BOTH -> String.format(BOTH_PROMPT_TEMPLATE, props.getMaxExpansionQueries(), query);
        };
    }

    private EnhanceResult parseAndBuild(String original, String raw, long elapsed) {
        // 截到最后一个 } 防尾随 markdown noise; 找不到 JSON 围栏 → parse_failed
        if (raw == null || raw.indexOf('{') < 0 || raw.lastIndexOf('}') <= raw.indexOf('{')) {
            log.warn(
                    "query_enhance.parse_failed mode={}, raw_head={}, err=no_json_in_response",
                    props.getMode(),
                    raw == null ? "" : raw.substring(0, Math.min(80, raw.length())));
            metrics.recordRewriteLatency(elapsed, "query_enhance_parse_failed");
            return EnhanceResult.failed(original, "parse_failed: no JSON in response", elapsed);
        }
        String jsonStr = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            String rewritten = original;
            List<String> expansions = new ArrayList<>();

            JsonNode rNode = root.get("rewritten");
            if (rNode != null && !rNode.isNull()) {
                String r = rNode.asText("").trim();
                if (!r.isEmpty()) {
                    rewritten = r;
                }
            }
            JsonNode eNode = root.get("expansions");
            if (eNode != null && eNode.isArray()) {
                for (JsonNode e : eNode) {
                    String s = e.asText("").trim();
                    if (!s.isEmpty()) {
                        expansions.add(s);
                    }
                    if (expansions.size() >= props.getMaxExpansionQueries()) break;
                }
            }

            // parrot-echo 检测: rewrite 没变 + 没扩展 → SKIP
            if (rewritten.equals(original) && expansions.isEmpty()) {
                metrics.recordRewriteLatency(elapsed, "query_enhance_skip");
                return EnhanceResult.skipped(original, elapsed);
            }

            metrics.recordRewriteLatency(elapsed, "query_enhance_ok");
            return EnhanceResult.success(original, rewritten, expansions, elapsed);
        } catch (Exception parseEx) {
            log.warn(
                    "query_enhance.parse_failed mode={}, raw_head={}, err={}",
                    props.getMode(),
                    raw == null ? "" : raw.substring(0, Math.min(80, raw.length())),
                    parseEx.getMessage());
            metrics.recordRewriteLatency(elapsed, "query_enhance_parse_failed");
            return EnhanceResult.failed(original, "parse_failed: " + parseEx.getMessage(), elapsed);
        }
    }

    /** 截取首个 `{` 到末个 `}` 之间 — 防 LLM 输出带 markdown ```json 围栏。 */
    private static String stripToJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return "{}";
        return s.substring(start, end + 1);
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
