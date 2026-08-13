package com.xxx.ragdoc.application.chat.comparison;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PR-6c / EMS-PR6c §3: Comparison Workflow Feature Flag + 服务端预算参数。
 *
 * <p>路径: {@code rag.agent.fixed-workflow.*}
 *
 * <ul>
 *   <li>{@code comparison-executor-enabled} 默认 false — Flag=false 走 PR-3 旧 ComparisonWorkflow; true
 *       走 AgentRunExecutor + ComparisonAnswerComposer 单次答案生成。
 *   <li>{@code compatibility-fallback-enabled} 默认 false — 仅在配置/初始化错误时回退旧路径; 权限/预算/timeout/Evidence
 *       缺失 <b>不</b>回退 (§3 硬约束)。
 *   <li>{@code max-steps} 默认 2 (left + right); {@code max-tool-calls} 默认 2; {@code max-evidence} 默认
 *       20; {@code max-evidence-tokens} 默认 4000。
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "rag.agent.fixed-workflow")
public class ComparisonExecutorProperties {

    /** Flag=true → 使用 AgentRunExecutor 新路径; false → 走 PR-3 旧 ComparisonWorkflow。 */
    private boolean comparisonExecutorEnabled = false;

    /** 仅在<b>显式开启</b>时, 配置缺失 / 初始化异常 / Bean 未注入允许兼容回退旧路径。 权限/预算/Evidence/timeout 失败永不回退 (§3)。 */
    private boolean compatibilityFallbackEnabled = false;

    /** 单 Run 最大 Step 数 — 默认 2 (left+right)。 */
    private int maxSteps = 2;

    /** 单 Run 最大真实 Tool Call 数 — 默认 2。 */
    private int maxToolCalls = 2;

    /** 单 Run deadline (毫秒) — 默认 30000。 */
    private long maxExecutionMillis = 30_000L;

    /** ComparisonEvidenceAccumulator 上限 — 默认 20。 */
    private int maxEvidence = 20;

    /** Evidence token 上限 (estimated) — 默认 4000。 */
    private int maxEvidenceTokens = 4000;

    public boolean isComparisonExecutorEnabled() {
        return comparisonExecutorEnabled;
    }

    public void setComparisonExecutorEnabled(boolean v) {
        this.comparisonExecutorEnabled = v;
    }

    public boolean isCompatibilityFallbackEnabled() {
        return compatibilityFallbackEnabled;
    }

    public void setCompatibilityFallbackEnabled(boolean v) {
        this.compatibilityFallbackEnabled = v;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int v) {
        this.maxSteps = v;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int v) {
        this.maxToolCalls = v;
    }

    public long getMaxExecutionMillis() {
        return maxExecutionMillis;
    }

    public void setMaxExecutionMillis(long v) {
        this.maxExecutionMillis = v;
    }

    public int getMaxEvidence() {
        return maxEvidence;
    }

    public void setMaxEvidence(int v) {
        this.maxEvidence = v;
    }

    public int getMaxEvidenceTokens() {
        return maxEvidenceTokens;
    }

    public void setMaxEvidenceTokens(int v) {
        this.maxEvidenceTokens = v;
    }
}
