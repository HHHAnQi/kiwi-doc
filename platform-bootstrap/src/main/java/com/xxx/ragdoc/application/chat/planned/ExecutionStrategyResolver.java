package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import org.springframework.stereotype.Component;

/**
 * PR-7c.3b / EMS-PR7 §4: 根据 RouterDecision + Feature Flag + confidence 决定 ExecutionStrategy。
 *
 * <p>在 RuleBasedTaskRouter 已产出 baseStrategy 后, 这里<b> Tür</b>:
 *
 * <ul>
 *   <li>intent == MULTI_HOP + confidence >= minRouterConfidence + planner.enabled + plannedPipeline.enabled
 *       + PlannerProvider available → 改为 PLANNED_AGENT
 *   <li>否则保留原 strategy (Classic/Targeted/FixedWorkflow; 不让 Flag 改 Router 分类)
 * </ul>
 *
 * <p>COMPARISON intent 不会进 PLANNED_AGENT — PR-7c.3 决策保留 COMPARISON → FIXED_WORKFLOW 优先
 * (Revision §4.3 表, 不能被 Planner 取代)。
 *
 * <p>能力与分类分离: Router 不感知 flag, 仅策略映射; Pipeline 用 {@code PLANNED_AGENT} 时
 * 由 PlannedAgentPipeline 持有 PlannerProvider 等组件, 若缺失 Pipeline Registry miss → 5xx 失败关闭,
 * 不假装成功 (兼容回退仅 Run 初始化前 + flag 显式开启)。
 */
@Component
public class ExecutionStrategyResolver {

    private final PlannerProperties plannerProperties;

    /** PR-7c.3b 总开关: planned-pipeline.enabled. 单独 ConfigProperties 防止循环依赖;
     * 默认 false; PR-7c.3 选择从 PlannerProperties 复用 enabled 标志 (语义等价)。 */
    private final boolean plannedPipelineEnabled;

    public ExecutionStrategyResolver(PlannerProperties plannerProperties) {
        this(plannerProperties, false /* planned-pipeline.enabled default */);
    }

    public ExecutionStrategyResolver(
            PlannerProperties plannerProperties, boolean plannedPipelineEnabled) {
        this.plannerProperties = plannerProperties;
        this.plannedPipelineEnabled = plannedPipelineEnabled;
    }

    /** 决策: 若满足 Planner 条件把原 strategy 升级为 PLANNED_AGENT; 否则原策略不变。 */
    public ExecutionStrategy resolve(RouterDecision decision, ExecutionStrategy baseStrategy) {
        if (!plannerProperties.isEnabled()) return baseStrategy;
        if (!plannedPipelineEnabled) return baseStrategy;
        if (decision == null) return baseStrategy;
        if (decision.intent() != com.xxx.ragdoc.application.chat.router.TaskIntent.MULTI_HOP) {
            return baseStrategy;
        }
        if (decision.confidence() < plannerProperties.getMinRouterConfidence()) return baseStrategy;
        // MULTI_HOP + Flag + confidence → PLANNED_AGENT
        return ExecutionStrategy.PLANNED_AGENT;
    }

    public boolean isPlannedPipelineEnabled() {
        return plannedPipelineEnabled;
    }

    /** PR-7c.3c 切换器: 允许 Pipeline 实际接线前自动 toggle (单测 / 集成测试用)。 */
    public ExecutionStrategyResolver withPlannedPipelineEnabled(boolean enabled) {
        return new ExecutionStrategyResolver(this.plannerProperties, enabled);
    }
}
