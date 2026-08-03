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

    /**
     * 触发 batch flush 的累计 observation 阈值(Phase 1.E)。
     *
     * <p>buffer 内任意 traceId 的 pending observation 数 ≥ 此值时, 立即触发 send, 不等 定时周期。
     * 设为 0 时禁用, 仅靠 flush-interval 周期 + endTrace 触发。
     */
    private int flushBatchSize = 50;

    /** 上报异步线程池大小。 */
    private int poolSize = 2;
}
