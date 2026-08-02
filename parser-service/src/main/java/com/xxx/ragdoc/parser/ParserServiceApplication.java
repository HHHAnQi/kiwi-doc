package com.xxx.ragdoc.parser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * parser-service 独立 Spring Boot 启动类(V3 第 1 周)。
 *
 * <p>纯 MQ 驱动消费 chat-app 发的 parse-task-submit, 完成异步解析 + 落库。 无 HTTP 入口(除 /actuator/health 用于 k3s
 * liveness probe)。
 *
 * <p>{@code @EnableScheduling} 启动 VisibilityTimeoutScheduler(V3 第 1 周 Commit 3 加)。
 *
 * <p>启动:
 *
 * <pre>
 * java -jar parser-service/build/libs/parser-service.jar
 *   --spring.profiles.active=dev
 *   --server.port=8093
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
public class ParserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ParserServiceApplication.class, args);
    }
}
