package com.xxx.ragdoc.application.chat.router;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PR-3.2 / EMS-PR3: 控制 ChatOrchestrator 是否在 AUTO 模式上启用 {@link TaskRouter}。
 *
 * <p>默认关闭 — AUTO 在 PR-2 中走 Classic RAG, PR-3 完整接入前继续走 Classic, 防止 Router 输出 TARGETED_RAG /
 * FIXED_WORKFLOW 时 Orchestrator 触发 PIPELINE_NOT_FOUND 走 500。
 *
 * <p>启用时机: PR-3.3 Targeted RAG / PR-3.4 Fixed Workflow 接入后再打开, 届时 AUTO 路径才真正按 RouterDecision 派发到对应
 * pipeline。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.router")
public class RouterProperties {
    /** {@code rag.router.enabled}, 默认 false。 */
    private boolean enabled = false;
}
