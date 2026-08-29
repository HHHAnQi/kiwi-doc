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

    /**
     * P0 修复(citations 错位): 预算器同时执行的字符总闸, 与 LLM client 的 max-context-chars 对齐并留出 [n] 编号开销余量(默认 3800
     * = 4000 - 200)。在此一次截断到位, 避免 client 内层 cap 再次 tail-drop 造成 ChatService 不可见的二次截断、重新打破 [n] 与
     * citations 的对齐。
     */
    private int contextMaxChars = 3800;
}
