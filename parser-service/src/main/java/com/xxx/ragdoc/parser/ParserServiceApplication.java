package com.xxx.ragdoc.parser;

import com.xxx.ragdoc.RagDocApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * parser-service 独立 Spring Boot 启动类(V3 第 1 周)。
 *
 * <p>纯 MQ 驱动消费 chat-app 发的 parse-task-submit, 完成异步解析 + 落库。 无 HTTP 入口(除 /actuator/health 用于 k3s
 * liveness probe)。
 *
 * <p><b>架构选择</b>: 为避免重复 infra 实现(MinioFileStorage / MilvusVectorStore / BgeM3EmbeddingClient /
 * Chunk/Document Jpa), parser-service 直接 project(":platform-bootstrap") 依赖, 复用其 infra adapter
 * 类(spec §6.1 parser-service 共享 schema + 共享基础设施)。本启动类用 ComponentScan filter:
 *
 * <ul>
 *   <li>扫 {@code com.xxx.ragdoc.parser.*} (parser-service 自有 service / consumer / scheduler)
 *   <li>扫 {@code com.xxx.ragdoc.infrastructure.*} (复用 platform-bootstrap 的 infra adapter)
 *   <li>扫 {@code com.xxx.ragdoc.application.document.chunking.*} (复用共享切片层)
 *   <li><b>排除</b> {@link RagDocApplication}, platform-bootstrap 的 chat / feedback / Controller /
 *       Filter 等 不需要的 chat-app 业务 Bean(由 ASSIGNABLE_TYPE filter 精确排除)
 * </ul>
 *
 * <p>{@code @EnableScheduling} 启动 VisibilityTimeoutScheduler(V3 第 1 周 Commit 3 加)。
 */
@SpringBootApplication(excludeName = {"com.xxx.ragdoc.RagDocApplication"})
@ComponentScan(
        basePackages = {"com.xxx.ragdoc.parser", "com.xxx.ragdoc.infrastructure"},
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "com\\.xxx\\.ragdoc\\.(interfaces|application\\.chat|application\\.feedback|application\\.chunk|application\\.auth|event).*"))
@EntityScan(basePackages = {"com.xxx.ragdoc.infrastructure.persistence.jpa.entity"})
@EnableJpaRepositories(basePackages = {"com.xxx.ragdoc.infrastructure.persistence.jpa.repository"})
@EnableScheduling
public class ParserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ParserServiceApplication.class, args);
    }
}
