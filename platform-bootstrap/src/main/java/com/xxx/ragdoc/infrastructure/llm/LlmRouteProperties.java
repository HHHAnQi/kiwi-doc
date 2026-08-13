package com.xxx.ragdoc.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 1.B (2026-08-03): LLM 多路由配置 primary / secondary / fallback。
 *
 * <p>设计: 启动期绑定三档路由(name + base-url + api-key + model + role), 由 {@link LlmRouter} 根据 role 决定调用顺序;
 * primary 失败 → fallback 自动接管。每档 route 独立 CircuitBreaker (resilience4j instance name = "llm-" +
 * role)。
 *
 * <p>yml 示例:
 *
 * <pre>{@code
 * rag:
 *   llm:
 *     primary-role: primary       # 默认 Route 名 ("primary"/"fallback")
 *     fallback-role: fallback     # 当 primary 熔断或异常时切到的 Route
 *     routes:
 *       - name: primary
 *         base-url: https://open.bigmodel.cn/api/paas/v4
 *         api-key: ${LLM_API_KEY}
 *         model: glm-4-plus
 *         timeout-ms: 60000
 *         max-tokens: 1024
 *         temperature: 0.3
 *       - name: fallback
 *         base-url: https://api.deepseek.com/v1
 *         api-key: ${LLM_FALLBACK_API_KEY}
 *         model: deepseek-chat
 *         timeout-ms: 60000
 *         max-tokens: 1024
 *         temperature: 0.3
 * }</pre>
 *
 * <p>V4 切自部署 vLLM 时, 加第三个 route "vllm" 即可, 代码不动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.llm")
public class LlmRouteProperties {

    /** 启动期绑定的 primary role 名。默认 "primary"。若该 role 在 routes 找不到,⋌退化到 routes.get(0)。 */
    private String primaryRole = "primary";

    /** Fallback role 名。默认 "fallback"。若找不到则只走 primary, 不做 fallback。 */
    private String fallbackRole = "fallback";

    /** Shadow role 名(Phase 1.B 不实装, 占位; Phase 1.C 跟上)。 */
    private String shadowRole;

    private List<Route> routes = new ArrayList<>();

    /** 单个路由条目。所有字段都支持 env 替换(yml 走 ${ENV_NAME} 语法)。 */
    @Data
    public static class Route {
        /** 路由实例名, 必须在 routes[] 里全局唯一。primary/fallback/vllm 等。 */
        private String name;

        /** OpenAI 兼容 endpoint, 例如 https://open.bigmodel.cn/api/paas/v4。 */
        private String baseUrl;

        /** Bearer token。 */
        private String apiKey;

        /** LLM model id, 例如 glm-4-plus / deepseek-chat / qwen-max。 */
        private String model;

        /** 单 route 级 timeout(ms), 默认 60000。 */
        private int timeoutMs = 60000;

        /** 单 route 级 maxTokens, 默认 1024。0 = 不传该字段。 */
        private int maxTokens = 1024;

        /** 单 route 级 temperature, 默认 0.3 (与 LlmProperties 默认一致)。 */
        private double temperature = 0.3;
    }

    /** 工具方法: 按 role 找 Route, 找不到返回 null。 */
    public Route findByRole(String role) {
        if (role == null) return null;
        return routes.stream().filter(r -> role.equals(r.getName())).findFirst().orElse(null);
    }
}
