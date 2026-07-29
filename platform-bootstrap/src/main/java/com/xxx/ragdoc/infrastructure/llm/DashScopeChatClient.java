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
                "你是 Spring Cloud Alibaba 技术文档助手,帮助用户理解 Nacos/Sentinel/Seata/RocketMQ 等组件。"
                        + "我会从知识库检索出与用户问题最相关的片段,标注为 [1][2][3]。回答规则:\n"
                        + "1. 优先根据片段给出具体可操作的解答,不要因信息不全直接拒答;\n"
                        + "2. 引用片段内容时用 [序号] 标注来源;\n"
                        + "3. 片段可能因 PDF 抽取含多余空行,这是格式噪声,忽略它聚焦正文;\n"
                        + "4. 片段只覆盖问题的部分角度时,基于已有内容尽可能作答;\n"
                        + "5. 只有片段与用户问题完全无关时,才回答\"知识库中没有相关内容\";";

        StringBuilder ctxBuilder = new StringBuilder();
        int totalChars = 0;
        int idx = 1;
        for (String chunkText : context) {
            if (chunkText == null || chunkText.isBlank()) continue;
            // PDF 抽取的 chunk 常含 3+ 连续 \n, 压成单个提升 LLM 可读性
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
                                + "\n请基于上述片段回答用户问题。问题: "
                                + query;

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
