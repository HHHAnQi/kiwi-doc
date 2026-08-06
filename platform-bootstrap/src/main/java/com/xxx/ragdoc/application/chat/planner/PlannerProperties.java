package com.xxx.ragdoc.application.chat.planner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PR-7 / EMS-PR7 §3: Planner + PlannedAgent Feature Flag + 服务端预算参数。
 *
 * <p>路径: {@code rag.agent.planner.*} 与 {@code rag.agent.planned-pipeline.*}
 *
 * <ul>
 *   <li>{@code rag.agent.planner.enabled} 默认 false — MULTI_HOP 是否允许走 Planning; 关时保持 PR-3/6c 路径
 *   <li>{@code rag.agent.planner.model-enabled} 默认 false — false → 用 Rule Template Planner; true → 调真实 LLM
 *   <li>{@code rag.agent.planner.max-plan-steps} 默认 3 (与 §6.2 budget 一致)
 *   <li>{@code rag.agent.planner.max-replans} 默认 1 (PR-7 硬上限; 多于 1 由 §7.5 loop-detect 阻断)
 *   <li>{@code rag.agent.planner.min-router-confidence} 默认 0.80 — 低于此值的多跳回退 Classic
 *   <li>{@code rag.agent.planned-pipeline.enabled} 默认 false — Pipeline 接线总开关
 *   <li>{@code rag.agent.planned-pipeline.compatibility-fallback-enabled} 默认 false
 * </ul>
 *
 * <p>本 PR (PR-7a) 大部分 Flag 仍 false; PR-7c 才接线 PlannedAgentPipeline。
 */
@Component
@ConfigurationProperties(prefix = "rag.agent.planner")
public class PlannerProperties {

    /** MULTI_HOP + Flag=true 才允许 Planner; Flag=false 保持 PR-3/6c。 */
    private boolean enabled = false;
    /** false→RuleTemplate (确定性 fixture); true→ModelPlanner (调 LLM JSON output)。 */
    private boolean modelEnabled = false;
    /** 单次 Plan 最多 Step 数。 */
    private int maxPlanSteps = 3;
    /** PR-7 硬上限: max-replans=1。 */
    private int maxReplans = 1;
    /** Router confidence 低于此阈值 → 不进入 Planner (低置信回退 Classic)。 */
    private double minRouterConfidence = 0.80;
    /** Model Planner 调用最大超时 (毫秒)。 */
    private long modelTimeoutMillis = 10_000;
    /** Model Planner 最大输出 token。 */
    private int modelMaxOutputTokens = 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isModelEnabled() { return modelEnabled; }
    public void setModelEnabled(boolean v) { this.modelEnabled = v; }

    public int getMaxPlanSteps() { return maxPlanSteps; }
    public void setMaxPlanSteps(int v) { this.maxPlanSteps = v; }

    public int getMaxReplans() { return maxReplans; }
    public void setMaxReplans(int v) { this.maxReplans = v; }

    public double getMinRouterConfidence() { return minRouterConfidence; }
    public void setMinRouterConfidence(double v) { this.minRouterConfidence = v; }

    public long getModelTimeoutMillis() { return modelTimeoutMillis; }
    public void setModelTimeoutMillis(long v) { this.modelTimeoutMillis = v; }

    public int getModelMaxOutputTokens() { return modelMaxOutputTokens; }
    public void setModelMaxOutputTokens(int v) { this.modelMaxOutputTokens = v; }
}
