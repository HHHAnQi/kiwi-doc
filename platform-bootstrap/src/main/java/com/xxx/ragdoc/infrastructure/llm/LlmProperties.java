package com.xxx.ragdoc.infrastructure.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 客户端配置(DashScope / OpenAI 兼容协议)。
 *
 * <pre>
 * llm:
 *   base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *   api-key: ${LLM_API_KEY:}        # 从 .env 注入, 不写死
 *   model: qwen-max
 *   timeout-ms: 60000              # LLM 首包可能慢, 国内 30s+
 *   max-context-chars: 4000        # 拼上下文给 LLM 的字符上限(防止 prompt 超长)
 * </pre>
 *
 * <p>切换 LLM: 改 base-url + model + api-key 三项即可, 代码不动。
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
}
