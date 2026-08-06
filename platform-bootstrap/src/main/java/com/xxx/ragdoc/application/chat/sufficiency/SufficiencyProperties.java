package com.xxx.ragdoc.application.chat.sufficiency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PR-7b / EMS-PR7 §3: Sufficiency Judge Feature Flag + 模型 fallback 参数。
 *
 * <p>路径: {@code rag.agent.sufficiency.*}
 *
 * <ul>
 *   <li>{@code enabled} 默认 false — 整体 Sufficiency Judge 开关 (PlannedAgentPipeline 内使用)
 *   <li>{@code model-fallback-enabled} 默认 false — Rule 无法判定时是否调 LLM Judge
 *   <li>{@code model-timeout-millis} 默认 5000
 *   <li>{@code model-max-output-tokens} 默认 512 (Judge 输出短 JSON)
 *   <li>{@code model-false-sufficient-guard} 默认 true — 即使 Model 输出 SUFFICIENT, 仍用 Rule
 *       再确认 COVERED-by-≥1-evidence (Revision §6.6 False Sufficient 防护, 由 RequirementCoverage ctor 兜底)
 * </ul>
 *
 * <p>所有 Flag 默认 false; PR-7c PlannedAgentPipeline 接通前 Sufficiency 不执行。
 */
@Component
@ConfigurationProperties(prefix = "rag.agent.sufficiency")
public class SufficiencyProperties {

    private boolean enabled = false;
    private boolean modelFallbackEnabled = false;
    private long modelTimeoutMillis = 5_000L;
    private int modelMaxOutputTokens = 512;
    private boolean modelFalseSufficientGuard = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isModelFallbackEnabled() { return modelFallbackEnabled; }
    public void setModelFallbackEnabled(boolean v) { this.modelFallbackEnabled = v; }

    public long getModelTimeoutMillis() { return modelTimeoutMillis; }
    public void setModelTimeoutMillis(long v) { this.modelTimeoutMillis = v; }

    public int getModelMaxOutputTokens() { return modelMaxOutputTokens; }
    public void setModelMaxOutputTokens(int v) { this.modelMaxOutputTokens = v; }

    public boolean isModelFalseSufficientGuard() { return modelFalseSufficientGuard; }
    public void setModelFalseSufficientGuard(boolean v) { this.modelFalseSufficientGuard = v; }
}
