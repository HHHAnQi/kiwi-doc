package com.xxx.ragdoc.application.chat.pipeline;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.online-execution")
public class OnlineExecutionProperties {
    private int contextTokenBudget = 3000;
    private long timeoutMs = 60000;
}
