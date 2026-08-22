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
 *   <li>intent == MULTI_HOP + confidence >= minRouterConfidence + planner.enabled +
 *       plannedPipeline.enabled + PlannerProvider available → 改为 PLANNED_AGENT
 *   <li>否则保留原 strategy (Classic/Targeted/FixedWorkflow; 不让 Flag 改 Router 分类)
 * </ul>
 *
 * <p>COMPARISON intent 不会进 PLANNED_AGENT — PR-7c.3 决策保留 COMPARISON → FIXED_WORKFLOW 优先 (Revision
 * §4.3 表, 不能被 Planner 取代)。
 *
 * <p>能力与分类分离: Router 不感知 flag, 仅策略映射; Pipeline 用 {@code PLANNED_AGENT} 时 由 PlannedAgentPipeline 持有
 * PlannerProvider 等组件, 若缺失 Pipeline Registry miss → 5xx 失败关闭, 不假装成功 (兼容回退仅 Run 初始化前 + flag 显式开启)。
 *
 * <p><b>PR-7f.2c-pre</b>: {@code plannedPipelineEnabled} 现在从 {@link PlannerProperties} 的 {@code
 * rag.agent.planner.planned-pipeline-enabled} 读取 (默认 false)。 单参构造器 (生产 component-scan) 仍等价于 {@code
 * plannedPipelineEnabled=false} —— 行为零差异。 双参构造器保留以便现有单元测试在 flag override 下直接构造, 不影响 Spring 绑定路径。
 */
@Component
public class ExecutionStrategyResolver {

    private final PlannerProperties plannerProperties;

    /**
     * 测试 override 入口 (双参 ctor / {@link #withPlannedPipelineEnabled(boolean)})。 生产路径下由 Spring 注入的
     * {@link PlannerProperties#isPlannedPipelineEnabled()} 提供, 不再硬编码 false。
     */
    private final boolean plannedPipelineEnabledOverride;

    private final boolean hasOverride;

    /**
     * 生产构造器 (Spring component-scan 默认调用): 无 override, 读 PlannerProperties 字段。
     * P1 修复: 双构造器并存且均无 @Autowired 时, Spring 回退找无参构造 → NoSuchMethodException
     * 启动失败(此前从未整包启动过所以未暴露)。显式标注本构造器为注入入口。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionStrategyResolver(PlannerProperties plannerProperties) {
        this.plannerProperties = plannerProperties;
        this.plannedPipelineEnabledOverride = false;
        this.hasOverride = false;
    }

    /** 测试构造器: 显式 override, 跳过 PlannerProperties 字段读取。 */
    public ExecutionStrategyResolver(
            PlannerProperties plannerProperties, boolean plannedPipelineEnabled) {
        this.plannerProperties = plannerProperties;
        this.plannedPipelineEnabledOverride = plannedPipelineEnabled;
        this.hasOverride = true;
    }

    /** 决策: 若满足 Planner 条件把原 strategy 升级为 PLANNED_AGENT; 否则原策略不变。 */
    public ExecutionStrategy resolve(RouterDecision decision, ExecutionStrategy baseStrategy) {
        if (!plannerProperties.isEnabled()) return baseStrategy;
        if (!isPlannedPipelineEnabled()) return baseStrategy;
        if (decision == null) return baseStrategy;
        if (decision.intent() != com.xxx.ragdoc.application.chat.router.TaskIntent.MULTI_HOP) {
            return baseStrategy;
        }
        if (decision.confidence() < plannerProperties.getMinRouterConfidence()) return baseStrategy;
        // MULTI_HOP + Flag + confidence → PLANNED_AGENT
        return ExecutionStrategy.PLANNED_AGENT;
    }

    /**
     * 实际生效的 flag 值: 优先使用 override (测试路径), 否则读 {@link PlannerProperties} 的配置字段。 单测仍可传 {@code new
     * ExecutionStrategyResolver(props, true)} 显式开启; 生产路径下由 {@code
     * rag.agent.planner.planned-pipeline-enabled} 决定 (默认 false)。
     */
    public boolean isPlannedPipelineEnabled() {
        return hasOverride
                ? plannedPipelineEnabledOverride
                : plannerProperties.isPlannedPipelineEnabled();
    }

    /** 显式 AGENTIC 模式的统一能力门禁，避免 Orchestrator 与 AUTO 路由使用两套开关判断。 */
    public boolean isAgenticModeAvailable() {
        return plannerProperties.isEnabled() && isPlannedPipelineEnabled();
    }

    /** PR-7c.3c 切换器: 允许 Pipeline 实际接线前自动 toggle (单测 / 集成测试用)。 */
    public ExecutionStrategyResolver withPlannedPipelineEnabled(boolean enabled) {
        return new ExecutionStrategyResolver(this.plannerProperties, enabled);
    }
}
