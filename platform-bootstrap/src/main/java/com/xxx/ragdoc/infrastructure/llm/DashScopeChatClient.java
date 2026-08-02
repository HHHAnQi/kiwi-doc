package com.xxx.ragdoc.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * {@link ChatClient} 的 DashScope(通义千问)实现。
 *
 * <p>走 OpenAI 兼容协议: POST {base-url}/chat/completions, body OpenAI 标准格式, Authorization Bearer 鉴权。
 *
 * <p>切换 LLM: 仿照本类新建 XxxChatClient, 配 base-url + model + key 即可。 本类只依赖 OpenAI 协议, 不调任何 DashScope 私有
 * API, 因此同样适用于 DeepSeek / Kimi / vLLM / Ollama 等 OpenAI 兼容服务商。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeChatClient implements ChatClient {

    private final LlmProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** WebClient 实例缓存(避免每次 chat 重建连接池, 性能更优)。 */
    private WebClient cachedClient;

    @PostConstruct
    void initClient() {
        this.cachedClient =
                WebClient.builder()
                        .baseUrl(props.getBaseUrl())
                        .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                        .defaultHeader("Content-Type", "application/json")
                        .build();
    }

    @Override
    public String chat(String query, List<String> context) throws Exception {
        ObjectNode body = buildOpenAiBody(query, context, false);

        // 3. POST /chat/completions (非流式)
        String respJson;
        try {
            respJson =
                    cachedClient
                            .post()
                            .uri("/chat/completions")
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofMillis(props.getTimeoutMs()))
                            .block();
        } catch (Exception e) {
            log.error(
                    "llm.call_failed model={}, query_len={}, error={}",
                    props.getModel(),
                    query.length(),
                    e.getMessage());
            throw e;
        }

        // 4. 解析 OpenAI 格式响应: choices[0].message.content
        JsonNode root = objectMapper.readTree(respJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("LLM 响应无 choices: " + respJson);
        }
        String content = choices.get(0).path("message").path("content").asText("");
        log.info(
                "llm.chat_done model={}, query_len={}, answer_len={}",
                props.getModel(),
                query.length(),
                content.length());

        return content;
    }

    /**
     * V3 W1: 流式 chat(SSE 端点用)。返回每个增量 token 的文本片段(Flux<String>)。
     *
     * <p>协议: OpenAI streaming — body 加 {@code "stream":true}, 响应 Server-Sent Events, 每行 {@code
     * data: {...}}, 增量在 {@code choices[0].delta.content}. 结束行 {@code data: [DONE]}.
     *
     * <p>错误处理: LLM 调用异常 → Flux.error(原异常), 让下游 Controller 在 onError 处理(发 SSE error 事件给前端)。
     */
    @Override
    public Flux<String> chatStream(String query, List<String> context) {
        ObjectNode body = buildOpenAiBody(query, context, true);

        return cachedClient
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class) // 每行 data: ... 作为一条
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .filter(line -> line != null && !line.isBlank() && !"[DONE]".equals(line.trim()))
                .mapNotNull(
                        line -> {
                            try {
                                JsonNode node = objectMapper.readTree(line);
                                String delta =
                                        node.path("choices")
                                                .path(0)
                                                .path("delta")
                                                .path("content")
                                                .asText("");
                                return delta.isEmpty() ? null : delta;
                            } catch (Exception parseEx) {
                                log.debug(
                                        "llm.stream.skip_non_json line_len={}",
                                        Math.min(line.length(), 200));
                                return null;
                            }
                        })
                .doOnComplete(
                        () ->
                                log.info(
                                        "llm.stream_done model={}, query_len={}",
                                        props.getModel(),
                                        query.length()))
                .doOnError(
                        e ->
                                log.error(
                                        "llm.stream_failed model={}, error={}",
                                        props.getModel(),
                                        e.getMessage()));
    }

    /** 抽出 commit d56a3e9 修复后的 body 构造逻辑, 同步与流式共享。 stream=true 时 LLM 走 SSE。 */
    private ObjectNode buildOpenAiBody(String query, List<String> context, boolean stream) {
        // 1. 组装 system prompt + user prompt(同 chat() 旧逻辑,不复制注释)
        String systemPrompt =
                "你是 Spring Cloud Alibaba 技术文档助手,帮助用户理解 Nacos/Sentinel/Seata/RocketMQ 等组件。"
                        + "我会从知识库检索出与用户问题最相关的片段,标注为 [1][2][3]。回答规则(务必遵守):\n"
                        + "1. 直接回答问题, 用 2-4 句要点作答, 不要写长篇分步骤教程, 不要复述问题;\n"
                        + "2. 答案必须从给定的片段中得出, 命中要点即可, 不引申不展开不举例;\n"
                        + "3. 片段里有的关键事实(配置项名、数值、版本号、步骤)要原样引用, 用 [序号] 标注来源;\n"
                        + "4. 片段含答案但只覆盖部分角度时, 只答片段里明确写到的部分, 其余角度不补不猜;\n"
                        + "5. 片段与问题完全无关时, 一句话回答\"知识库中没有相关内容\";\n"
                        + "6. 片段可能因 PDF 抽取含多余空行, 这是格式噪声, 忽略它聚焦正文;";

        StringBuilder ctxBuilder = new StringBuilder();
        int totalChars = 0;
        int idx = 1;
        for (String chunkText : context) {
            if (chunkText == null || chunkText.isBlank()) continue;
            chunkText = chunkText.replaceAll("\\n{2,}", "\n").trim();
            if (totalChars + chunkText.length() > props.getMaxContextChars()) {
                int remain = props.getMaxContextChars() - totalChars;
                if (remain <= 0) break;
                chunkText = chunkText.substring(0, remain);
            }
            ctxBuilder.append("[").append(idx).append("] ").append(chunkText).append("\n\n");
            totalChars += chunkText.length();
            idx++;
        }
        String userPrompt =
                ctxBuilder.length() == 0
                        ? query
                        : "下面是从知识库检索到的相关片段:\n\n"
                                + ctxBuilder
                                + "\n请基于上述片段直接回答用户问题(2-4 句要点)。问题: "
                                + query;

        // 2. 构造 OpenAI 兼容 body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", 0.3);
        body.put("stream", stream);
        // commit d56a3e9: GLM-4-plus 显式禁用 thinking(否则 content 被截 11 字)
        ObjectNode thinkingDisabled = objectMapper.createObjectNode();
        thinkingDisabled.put("type", "disabled");
        body.set("thinking", thinkingDisabled);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        return body;
    }
}
