package com.xxx.ragdoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用主入口。V1 单体形态;V3 微服务化后将拆分为各服务的独立 Application 类。
 *
 * <p>Phase 3 / P3-2: {@code @EnableScheduling} 启用 {@code MilvusDeleteSweeper} 定时收敛软删文档的
 * Milvus 向量 (软删主流程走 circuit breaker 失败时 mark pending, sweeper 兜底重试)。
 */
@SpringBootApplication
@EnableScheduling
public class RagDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagDocApplication.class, args);
    }
}
