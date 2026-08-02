package com.xxx.ragdoc.infrastructure.trace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Langfuse 配置(V3-W3)。
 *
 * <p>KEY 留空(默认值)时 Langfuse HTTP 实现 Bean 不装配, 走 {@link NoOpTraceObserver} 兜底。
 *
 * <p>启动本地 Langfuse: docker run langfuse/langfuse:latest 或 docker compose
 * https://raw.githubusercontent.com/langfuse/langfuse/main/docker-compose.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 总开关。false 时全程 no-op, 不连 Langfuse, 不影响业务。 */
    private boolean enabled = false;

    /** Langfuse 服务地址(自部署或 cloud.langfuse.com)。 */
    private String baseUrl = "http://localhost:3000";

    /** Langfuse project public key(pk-lf-...)。 */
    private String publicKey;

    /** Langfuse project secret key(sk-lf-...)。 */
    private String secretKey;

    /** 上报 batch flush 间隔(ms)。 */
    private long flushIntervalMs = 5000;

    /** 上报异步线程池大小。 */
    private int poolSize = 2;
}
