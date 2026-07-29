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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
        // 1. 组装 system prompt + user prompt
        // System: 角色定位 + 答题规则(只用 context, 拒答无关问题, 引用 [n])
        // User: 拼接 [1] chunk1 [2] chunk2 ... + 原始 query
        String systemPrompt =
                "你是 Spring Cloud Alibaba 技术文档助手。根据下方提供的检索片段回答问题。"
                        + "要求: 1) 答案必须基于提供的片段; "
                        + "2) 引用片段时用 [1][2] 的方括号序号; "
                        + "3) 片段中没有相关信息时, 直接回答\"知识库中没有相关内容\", 不要编造。";

        StringBuilder ctxBuilder = new StringBuilder();
        int totalChars = 0;
        int idx = 1;
        for (String chunkText : context) {
            if (chunkText == null || chunkText.isBlank()) continue;
            // 截到 maxContextChars 上限为止, 防止 prompt 超模型最大输入
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
                ctxBuilder.length() == 0 ? query : "参考资料:\n" + ctxBuilder + "\n问题: " + query;

        // 2. 构造 OpenAI 兼容 body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", 0.3); // 文档问答场景要确定性, 低温度
        ArrayNode messages = body.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        // 3. POST /chat/completions
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
}
