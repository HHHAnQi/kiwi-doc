package com.xxx.ragdoc.infrastructure.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 客户端配置(DashScope / OpenAI 兼容协议)。
 *
 * <p>切换 LLM: 改 base-url + model + api-key 三项即可, 代码不动。
 *
 * <p>Phase 1.B (2026-08-03): 加 maxTokens / temperature 字段(之前 DashScopeChatClient
 * 写死 temperature=0.3)。这两个字段在多 route / fallback 切换时不一定一样, 让 yml 控制。
 * rag.llm.routes[i].* 可独立覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "qwen-max";
    private int timeoutMs = 60000;
    private int maxContextChars = 4000;

    /** Phase 1.B: 默认 MaxTokens。LLM 答案 token 上限(保护成本)。0 = 不传该字段。 */
    private int maxTokens = 1024;

    /** Phase 1.B: 默认 temperature。0.3 与之前 DashScopeChatClient 写死值一致(baseline 行为)。 */
    private double temperature = 0.3;
}
