package com.xxx.ragdoc.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chat-app 通用 Bean 配置 补充(V3 添加).
 *
 * <p>{@link Clock} 用 UTC 系统时钟, 注入 ParseTaskProducer / ParseTaskService(若 chat-app 也需本地守护) / 其他
 * 需要可测试时间戳的服务(测试可传 fixed Clock).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
