package com.xxx.ragdoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用主入口。V1 单体形态;V3 微服务化后将拆分为各服务的独立 Application 类。
 */
@SpringBootApplication
public class RagDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagDocApplication.class, args);
    }
}
