package com.xxx.ragdoc.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.ChatMessages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Phase 1.B (2026-08-03): OpenAI 兼容 LLM 客户端, 不绑死单一 base_url / api_key。
 *
 * <p>设计: 每个 {@link LlmRouteProperties.Route} 对应一个独立 {@code OpenAiCompatibleLlmClient}
 * 实例(独立 WebClient / Route 配置), {@link LlmRouter} 按 primary/fallback 顺序 + CircuitBreaker
 * 装饰调用。
 *
 * <p>本类实现 {@link com.xxx.ragdoc.application.chat.port.ChatClient} 接口, 同时保留 Phase 0/2.0
 * 锁定的 baseline 行为(prompt 构造 + DashScope HTTP 协议), 让多 route 切换零回归。
 *
 * <p>无状态 client — 实例字段仅 WebClient / Route config / ChatMessages flag, 业务状态全部在
 * 调用栈参数里, 线程安全。
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements com.xxx.ragdoc.application.chat.port.ChatClient {

    private final LlmRouteProperties.Route route;
    /** 全局配置, 目前只用 maxContextChars(route 级没单独字段)。 */
    private final LlmProperties globalProps;
    /** Phase 2.A: 读 promptRelaxRefusal flag 决定 baseline vs relaxed prompt。 */
    private final ChatMessages chatMessages;
    private final WebClient cachedClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Phase 3 / P3-5: 上一次同步 chat 调用的 usage (volatile 弱一致)。SSE / 并发同步调用时 last-write-wins,
     * best-effort 取值; caller 应在 chat() 返回后立即取出。null 表示响应 usage 块缺失/解析失败。
     */
    private volatile com.xxx.ragdoc.application.chat.port.ChatClient.TokenUsage lastUsage;

    // WebClient 缓存: ConcurrentHashMap 防止 @PostConstruct 期多线程并发创建多个 WebClient。
    private static final Map<String, WebClient> CLIENT_POOL = new ConcurrentHashMap<>();

    public OpenAiCompatibleLlmClient(
            LlmRouteProperties.Route route, LlmProperties globalProps, ChatMessages chatMessages) {
        this.route = route;
        this.globalProps = globalProps;
        this.chatMessages = chatMessages;
        String cacheKey = route.getBaseUrl() + "|" + route.getApiKey();
        this.cachedClient = CLIENT_POOL.computeIfAbsent(
                cacheKey,
                k -> WebClient.builder()
                        .baseUrl(route.getBaseUrl())
                        .defaultHeader("Authorization", "Bearer " + route.getApiKey())
                        .defaultHeader("Content-Type", "application/json")
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build());
    }

    public String getRouteName() {
        return route.getName();
    }

    public String getModel() {
        return route.getModel();
    }

    /** Phase 3 / P3-5: token counter tag 用, 不分业务分支。 */
    @Override
    public String currentModel() {
        return route.getModel();
    }

    /**
     * Phase 3 / P3-5: 调用方同步 chat 之后立即取; 不保证并发 / 长间隔后的准确性。
     * 返回 empty 表示响应 usage 缺失或还没跑过 chat。
     */
    @Override
    public java.util.Optional<com.xxx.ragdoc.application.chat.port.ChatClient.TokenUsage> lastUsage() {
        return java.util.Optional.ofNullable(lastUsage);
    }

    // ─── ChatClient 接口实现 ────────────────────────────────

    @Override
    public String chat(String query, List<String> context) throws Exception {
        ObjectNode body = buildOpenAiBody(query, context, false);
        String respJson;
        try {
            respJson = cachedClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(route.getTimeoutMs()))
                    .block();
        } catch (Exception e) {
            log.error("llm.call_failed route={}, model={}, query_len={}, error={}",
                    route.getName(), route.getModel(), query.length(), e.getMessage());
            throw e;
        }

        JsonNode root = objectMapper.readTree(respJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("LLM 响应无 choices: " + respJson);
        }
        String content = choices.get(0).path("message").path("content").asText("");
        // Phase 3 / P3-5: 解析 usage 块 (OpenAI 兼容标配, 所有主流后端都返回)。失败不抛错, 仅清空 lastUsage。
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && usage.isObject()) {
            int prompt = usage.path("prompt_tokens").asInt(0);
            int completion = usage.path("completion_tokens").asInt(0);
            int total = usage.path("total_tokens").asInt(prompt + completion);
            lastUsage =
                    new com.xxx.ragdoc.application.chat.port.ChatClient.TokenUsage(
                            prompt, completion, total);
            log.info(
                    "llm.chat_done route={}, model={}, query_len={}, answer_len={}, prompt_tok={}, completion_tok={}",
                    route.getName(),
                    route.getModel(),
                    query.length(),
                    content.length(),
                    prompt,
                    completion);
        } else {
            lastUsage = null;
            log.info(
                    "llm.chat_done route={}, model={}, query_len={}, answer_len={}, usage=missing",
                    route.getName(),
                    route.getModel(),
                    query.length(),
                    content.length());
        }
        return content;
    }

    @Override
    public Flux<String> chatStream(String query, List<String> context) {
        ObjectNode body = buildOpenAiBody(query, context, true);
        return cachedClient.post()
                .uri("/chat/completions")
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofMillis(route.getTimeoutMs()))
                .onErrorMap(e -> {
                    log.error("llm.stream_failed route={}, model={}, query_len={}, error={}",
                            route.getName(), route.getModel(), query.length(), e.getMessage());
                    return e;
                })
                .flatMap(chunk -> {
                    if ("[DONE]".equals(chunk.trim())) {
                        return Flux.empty();
                    }
                    try {
                        JsonNode root = objectMapper.readTree(chunk);
                        String delta = root.path("choices").get(0).path("delta").path("content").asText("");
                        return delta.isEmpty() ? Flux.empty() : Flux.just(delta);
                    } catch (Exception parseErr) {
                        log.warn("llm.stream_chunk_parse_failed route={}, chunk={}, err={}",
                                route.getName(), chunk.substring(0, Math.min(80, chunk.length())),
                                parseErr.getMessage());
                        return Flux.empty();
                    }
                })
                .doOnComplete(() -> log.info("llm.stream_done route={}, model={}",
                        route.getName(), route.getModel()));
    }

    // ─── prompt + body 构造 (与 DashScopeChatClient 老实现等价, 提拆出) ─────

    private ObjectNode buildOpenAiBody(String query, List<String> context, boolean stream) {
        String systemPrompt = (chatMessages != null && chatMessages.isPromptRelaxRefusal())
                ? buildRelaxedPrompt()
                : buildBaselinePrompt();

        StringBuilder ctxBuilder = new StringBuilder();
        int totalChars = 0;
        int idx = 1;
        for (String chunkText : context) {
            if (chunkText == null || chunkText.isBlank()) continue;
            chunkText = chunkText.replaceAll("\\n{2,}", "\n").trim();
            int maxCtx = globalProps.getMaxContextChars();
            if (totalChars + chunkText.length() > maxCtx) {
                int remain = maxCtx - totalChars;
                if (remain <= 0) break;
                chunkText = chunkText.substring(0, remain);
            }
            ctxBuilder.append("[").append(idx).append("] ").append(chunkText).append("\n\n");
            totalChars += chunkText.length();
            idx++;
        }
        String userPrompt = ctxBuilder.length() == 0
                ? query
                : "下面是从知识库检索到的相关片段:\n\n" + ctxBuilder
                        + "\n请基于上述片段直接回答用户问题(2-4 句要点)。问题: " + query;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", route.getModel());
        body.put("temperature", route.getTemperature());
        body.put("stream", stream);
        if (route.getMaxTokens() > 0) {
            body.put("max_tokens", route.getMaxTokens());
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        return body;
    }

    /** baseline prompt (Phase 0/2.0 锁定, 默认)。 */
    private String buildBaselinePrompt() {
        return "你是 Spring Cloud Alibaba 技术文档助手,帮助用户理解 Nacos/Sentinel/Seata/RocketMQ 等组件。"
                + "我会从知识库检索出与用户问题最相关的片段,标注为 [1][2][3]。回答规则(务必遵守):\n"
                + "1. 直接回答问题, 用 2-4 句要点作答, 不要写长篇分步骤教程, 不要复述问题;\n"
                + "2. 答案必须从给定的片段中得出, 命中要点即可, 不引申不展开不举例;\n"
                + "3. 片段里有的关键事实(配置项名、数值、版本号、步骤)要原样引用, 用 [序号] 标注来源;\n"
                + "4. 片段含答案但只覆盖部分角度时, 只答片段里明确写到的部分, 其余角度不补不猜;\n"
                + "5. 片段与问题完全无关时, 一句话回答\"知识库中没有相关内容\";\n"
                + "6. 片段可能因 PDF 抽取含多余空行, 这是格式噪声, 忽略它聚焦正文;";
    }

    /** Phase 2.A 放宽 prompt (flag ON 时启用, 默认 OFF)。 */
    private String buildRelaxedPrompt() {
        return "你是 Spring Cloud Alibaba 技术文档助手,帮助用户理解 Nacos/Sentinel/Seata/RocketMQ 等组件。"
                + "我会从知识库检索出与用户问题最相关的片段,标注为 [1][2][3]。回答规则(务必遵守):\n"
                + "1. 直接回答问题, 用 2-4 句要点作答, 不要写长篇分步骤教程, 不要复述问题;\n"
                + "2. 答案必须从给定的片段中得出, 命中要点即可, 不引申不展开不举例;\n"
                + "3. 片段里有的关键事实(配置项名、数值、版本号、步骤)要原样引用, 用 [序号] 标注来源;\n"
                + "4. 片段含答案但只覆盖部分角度时, 只答片段里明确写到的部分, 其余角度不补不猜;\n"
                + "5. 片段与问题的判定规则(严格遵守, 避免误判): \n"
                + "   a. 只有当 [所有片段] 都和问题主题词在不同领域(如问Nacos但片段全讲RocketMQ)、"
                + "或片段只是无意义占位/目录页/版权页时, 才回答\"知识库中没有相关内容\";\n"
                + "   b. 片段含相关 [代码示例/配置片段/类名/方法名] 时, 即使没有自然语言解释, "
                + "也要基于该代码用1-2句话作答;\n"
                + "   c. 片段讨论同一组件时, 给出与该片段直接相关的答案, "
                + "不要因'片段没逐字命中问题'就拒答;\n"
                + "6. 片段可能因 PDF 抽取含多余空行, 这是格式噪声, 忽略它聚焦正文;\n"
                + "7. 严禁编造任何片段中没有的版本号、数值、配置项名;";
    }
}
