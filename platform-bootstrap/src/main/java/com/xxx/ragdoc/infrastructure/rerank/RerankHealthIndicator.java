package com.xxx.ragdoc.infrastructure.rerank;

import com.xxx.ragdoc.application.chat.RerankProperties;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reranker 连接检测(2026-08-24): actuator health 组件 — `GET /actuator/health` 的
 * components.rerank 直接反映 GPU reranker 链路(SSH 隧道 + 远端服务)是否可用。
 *
 * <p>动机(实测教训): 隧道断开/端口配错时系统会静默降级 hybrid(rerank_state=failed
 * 只在日志), 一次评测对照因此拿到过降级数据。运维需要一条命令判断:
 * {@code curl -s localhost:8080/actuator/health | jq .components.rerank}。
 *
 * <p>探测策略: 对 base-url 的 /health 发 2s 超时 GET — UP 携带 model/device,
 * DOWN 携带错误与 base-url(降级提示)。仅在 rag.rerank.enabled=true 时注册;
 * 探测本身无副作用, 不影响检索主链路。
 */
@Slf4j
@Component("rerank")
@ConditionalOnProperty(prefix = "rag.rerank", name = "enabled", havingValue = "true")
public class RerankHealthIndicator implements HealthIndicator {

    private final RerankProperties props;
    private final WebClient client;

    public RerankHealthIndicator(RerankProperties props) {
        this.props = props;
        this.client = WebClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public Health health() {
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            return Health.unknown().withDetail("reason", "base-url 未配置").build();
        }
        try {
            String body =
                    client.get()
                            .uri("/health")
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(2))
                            .block();
            boolean ok = body != null && body.contains("\"status\":\"ok\"");
            if (ok) {
                return Health.up()
                        .withDetail("base-url", props.getBaseUrl())
                        .withDetail("probe-response", body == null ? "" : body.substring(0, Math.min(200, body.length())))
                        .build();
            }
            return Health.down()
                    .withDetail("base-url", props.getBaseUrl())
                    .withDetail("reason", "探测响应非 ok: " + (body == null ? "empty" : body.substring(0, Math.min(120, body.length()))))
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("base-url", props.getBaseUrl())
                    .withDetail("error", e.getMessage())
                    .withDetail("impact", "检索自动降级 hybrid 序(rerank 失效, 质量回落)")
                    .build();
        }
    }
}
