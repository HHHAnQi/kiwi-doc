package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.planner.PlannerProperties;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7c.3b: {@link ExecutionStrategyResolver} MULTI_HOP → PLANNED_AGENT 能力门禁。 */
@DisplayName("ExecutionStrategyResolver - PR-7c.3b MULTI_HOP PLANNED_AGENT 门禁")
class ExecutionStrategyResolverTest {

    private PlannerProperties props;

    @BeforeEach
    void setup() {
        props = new PlannerProperties();
        props.setEnabled(true); // default enabled for these tests
        props.setMinRouterConfidence(0.80);
    }

    private RouterDecision decision(TaskIntent intent, double confidence) {
        return new RouterDecision(
                intent, ExecutionStrategy.FIXED_WORKFLOW, List.of(), Map.of(), confidence, "TEST");
    }

    @Test
    @DisplayName("MULTI_HOP + 高置信 + Flags=true → PLANNED_AGENT")
    void multiHopHighConfidenceBecomesPlanned() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.MULTI_HOP, 0.9), ExecutionStrategy.FIXED_WORKFLOW);
        assertThat(s).isEqualTo(ExecutionStrategy.PLANNED_AGENT);
    }

    @Test
    @DisplayName("planner.enabled=false → 原 strategy 保留")
    void plannerDisabledKeepsStrategy() {
        props.setEnabled(false);
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.MULTI_HOP, 0.95), ExecutionStrategy.FIXED_WORKFLOW);
        assertThat(s).isEqualTo(ExecutionStrategy.FIXED_WORKFLOW);
    }

    @Test
    @DisplayName("plannedPipeline.enabled=false → 原 strategy 保留")
    void pipelineDisabledKeepsStrategy() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, false);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.MULTI_HOP, 0.95), ExecutionStrategy.CLASSIC_RAG);
        assertThat(s).isEqualTo(ExecutionStrategy.CLASSIC_RAG);
    }

    @Test
    @DisplayName("confidence 低于阈值 (0.6 < 0.80) → 原路径")
    void lowConfidenceKeepsStrategy() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.MULTI_HOP, 0.6), ExecutionStrategy.CLASSIC_RAG);
        assertThat(s).isEqualTo(ExecutionStrategy.CLASSIC_RAG);
    }

    @Test
    @DisplayName("COMPARISON intent 即使高置信也不进 PLANNED_AGENT (PR-4.3 + 6c 偏好固定工作流)")
    void comparisonStaysFixedWorkflow() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.COMPARISON, 0.95), ExecutionStrategy.FIXED_WORKFLOW);
        assertThat(s).isEqualTo(ExecutionStrategy.FIXED_WORKFLOW);
    }

    @Test
    @DisplayName("FACT intent 不进 Planner")
    void factStaysClassic() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.FACT, 0.95), ExecutionStrategy.CLASSIC_RAG);
        assertThat(s).isEqualTo(ExecutionStrategy.CLASSIC_RAG);
    }

    @Test
    @DisplayName("ENTITY_LOOKUP 不进 Planner (TARGETED_RAG)")
    void entityLookupStaysTargeted() {
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(props, true);
        ExecutionStrategy s =
                r.resolve(decision(TaskIntent.ENTITY_LOOKUP, 0.95), ExecutionStrategy.TARGETED_RAG);
        assertThat(s).isEqualTo(ExecutionStrategy.TARGETED_RAG);
    }

    // ─── PR-7f.2c-pre: 配置注入路径 (@ConfigurationProperties 字段) ───

    @Test
    @DisplayName("PR-7f.2c-pre: 单参 ctor 读 PlannerProperties.isPlannedPipelineEnabled (true) → 升级")
    void singleArgCtorReadsPlannerPropertiesFlagWhenTrue() {
        PlannerProperties p = new PlannerProperties();
        p.setEnabled(true);
        p.setMinRouterConfidence(0.80);
        p.setPlannedPipelineEnabled(true);
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(p); // 生产构造器
        assertThat(r.isPlannedPipelineEnabled()).isTrue();
        assertThat(
                        r.resolve(
                                decision(TaskIntent.MULTI_HOP, 0.95),
                                ExecutionStrategy.FIXED_WORKFLOW))
                .isEqualTo(ExecutionStrategy.PLANNED_AGENT);
    }

    @Test
    @DisplayName("PR-7f.2c-pre: 单参 ctor PlannerProperties.plannedPipelineEnabled=false (默认) → 不升级")
    void singleArgCtorReadsPlannerPropertiesFlagDefaultFalse() {
        PlannerProperties p = new PlannerProperties(); // 默认 false
        p.setEnabled(true);
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(p);
        assertThat(r.isPlannedPipelineEnabled()).isFalse();
        assertThat(r.resolve(decision(TaskIntent.MULTI_HOP, 0.95), ExecutionStrategy.CLASSIC_RAG))
                .isEqualTo(ExecutionStrategy.CLASSIC_RAG); // 原策略不变 — zero-diff
    }

    @Test
    @DisplayName("PR-7f.2c-pre: 双参 ctor override 优先于 PlannerProperties 字段 (测试 API 不破坏)")
    void twoArgCtorOverridesPropertyField() {
        PlannerProperties p = new PlannerProperties();
        p.setEnabled(true);
        p.setPlannedPipelineEnabled(false); // 字段关
        ExecutionStrategyResolver r = new ExecutionStrategyResolver(p, true); // override 开
        assertThat(r.isPlannedPipelineEnabled()).isTrue(); // override 胜出
        assertThat(
                        r.resolve(
                                decision(TaskIntent.MULTI_HOP, 0.95),
                                ExecutionStrategy.FIXED_WORKFLOW))
                .isEqualTo(ExecutionStrategy.PLANNED_AGENT);
    }
}
