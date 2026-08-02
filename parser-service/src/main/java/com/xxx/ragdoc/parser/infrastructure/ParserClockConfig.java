package com.xxx.ragdoc.parser.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * parser-service 通用 Bean 配置。
 *
 * <p>{@link Clock} 用 UTC 系统时钟, 注入 ParseTaskService 让状态迁移时间戳可测试(测试可传 fixed Clock)。
 */
@Configuration
public class ParserClockConfig {

    @Bean
    public Clock parserClock() {
        return Clock.systemUTC();
    }
}
