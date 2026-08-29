package com.xxx.ragdoc.application.chat.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P0-1(降级链): Model → retry → Rule 运行时降级链全场景验收。
 *
 * <p>对应验收矩阵: Model success / transient→retry success / Model fail→Rule success / Model+Rule
 * fail→ALL_PLANNERS_FAILED(Pipeline 层再降 Classic, 见 PlannedAgentPipeline) / model-disabled
 * zero-diff。另覆盖: FIXTURE_* 确定性失败不重试(REPLAY 评测语义)、 Rule 返回 null (无 allowed tool) 视同失败、降级指标接线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FallbackPlannerProvider - P0-1 Planner 运行时降级链")
class FallbackPlannerProviderTest {

    @Mock private ModelPlannerProvider modelProvider;
    @Mock private RuleTemplatePlannerProvider ruleProvider;
    @Mock private MetricsPort metrics;
    private PlannerProperties props;
    private FallbackPlannerProvider chain;

    @BeforeEach
    void setup() {
        props = new PlannerProperties(); // retry=1, ruleFallback=true (生产默认)
        chain = new FallbackPlannerProvider(modelProvider, ruleProvider, props, metrics);
    }

    private PlannerRequest request() {
        return new PlannerRequest(
                "run-1",
                "q",
                TaskIntent.MULTI_HOP,
                List.of(),
                Map.of(),
                List.of(EvidenceRequirement.fact("R1", "d", true)),
                EvidenceCoverageSummary.empty(),
                List.of(),
                new AgentBudgetView(3, 3, 3, 3, 30000, 1),
                List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                0);
    }

    private static PlannerResponse resp(String planId) {
        return new PlannerResponse(planId, "v1", List.of(), List.of(), "");
    }

    @Test
    @DisplayName("Model 成功 → 直接返回, 不触碰 Rule, 无降级指标")
    void modelSuccess() {
        when(modelProvider.plan(any())).thenReturn(resp("model-plan"));
        PlannerResponse out = chain.plan(request());
        assertThat(out.planId()).isEqualTo("model-plan");
        assertThat(out.reasonCode()).isEmpty();
        verify(ruleProvider, never()).plan(any());
        verify(metrics, never()).incrementPlannerDegradation(any());
    }

    @Test
    @DisplayName("Model 瞬态失败 → 重试成功: Model 恰好 2 次调用, Rule 0 次, 指标 model_retry_success")
    void retrySuccess() {
        AtomicInteger calls = new AtomicInteger();
        when(modelProvider.plan(any()))
                .thenAnswer(
                        inv -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new PlannerException(
                                        PlannerException.Reason.TIMEOUT, "transient");
                            }
                            return resp("model-plan");
                        });
        PlannerResponse out = chain.plan(request());
        assertThat(out.planId()).isEqualTo("model-plan");
        verify(modelProvider, times(2)).plan(any());
        verify(ruleProvider, never()).plan(any());
        verify(metrics).incrementPlannerDegradation("model_retry_success");
    }

    @Test
    @DisplayName("Model 重试耗尽 → Rule 兜底成功: 结果带 RULE_FALLBACK 标记, 指标 rule_fallback")
    void ruleFallback() {
        when(modelProvider.plan(any()))
                .thenThrow(
                        new PlannerException(PlannerException.Reason.PROVIDER_ERROR, "llm down"));
        when(ruleProvider.plan(any())).thenReturn(resp("rule-plan"));
        PlannerResponse out = chain.plan(request());
        assertThat(out.planId()).isEqualTo("rule-plan");
        assertThat(out.reasonCode())
                .startsWith(FallbackPlannerProvider.REASON_RULE_FALLBACK)
                .contains("PROVIDER_ERROR") // 失败原因可追溯
                .contains("att2"); // Model 尝试次数可追溯
        verify(modelProvider, times(2)).plan(any()); // 1 + retry 1
        verify(metrics).incrementPlannerDegradation("rule_fallback");
    }

    @Test
    @DisplayName("Model+Rule 全灭 → PlannerException(ALL_PLANNERS_FAILED), 由 Pipeline 降级 Classic")
    void allPlannersFailed() {
        when(modelProvider.plan(any()))
                .thenThrow(
                        new PlannerException(PlannerException.Reason.PROVIDER_ERROR, "llm down"));
        when(ruleProvider.plan(any())).thenThrow(new IllegalStateException("rule planner broken"));
        assertThatThrownBy(() -> chain.plan(request()))
                .isInstanceOf(PlannerException.class)
                .hasMessageContaining("ALL_PLANNERS_FAILED");
    }

    @Test
    @DisplayName("Rule 返回 null (无 allowed tool) → 视同失败, ALL_PLANNERS_FAILED")
    void ruleNullTreatedAsFailure() {
        when(modelProvider.plan(any()))
                .thenThrow(new PlannerException(PlannerException.Reason.TIMEOUT, "t"));
        when(ruleProvider.plan(any())).thenReturn(null);
        assertThatThrownBy(() -> chain.plan(request()))
                .isInstanceOf(PlannerException.class)
                .hasMessageContaining("ALL_PLANNERS_FAILED");
    }

    @Test
    @DisplayName("model-enabled=false (无 Model bean) → 纯转发 Rule, zero-diff")
    void modelDisabledZeroDiff() {
        FallbackPlannerProvider fwd =
                new FallbackPlannerProvider(null, ruleProvider, props, metrics);
        when(ruleProvider.plan(any())).thenReturn(resp("rule-plan"));
        PlannerResponse out = fwd.plan(request());
        assertThat(out.planId()).isEqualTo("rule-plan");
        assertThat(out.reasonCode()).isEmpty(); // 不加降级标记 — 非降级路径
        verify(modelProvider, never()).plan(any());
    }

    @Test
    @DisplayName("重试成功 → reasonCode 带 MODEL_RETRY_SUCCESS:attN 标记 (仍为 MODEL 来源)")
    void retrySuccessMarker() {
        AtomicInteger calls = new AtomicInteger();
        when(modelProvider.plan(any()))
                .thenAnswer(
                        inv -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new PlannerException(
                                        PlannerException.Reason.TIMEOUT, "transient");
                            }
                            return resp("model-plan");
                        });
        PlannerResponse out = chain.plan(request());
        assertThat(out.reasonCode())
                .startsWith(FallbackPlannerProvider.REASON_MODEL_RETRY)
                .contains("att2");
    }

    @Test
    @DisplayName("FIXTURE_UNAVAILABLE (REPLAY 夹具缺失) → 严格失败: 不重试不降级Rule, Model 恰好 1 次调用")
    void fixtureFailureStrictFail() {
        when(modelProvider.plan(any()))
                .thenThrow(
                        new PlannerException(
                                PlannerException.Reason.FIXTURE_UNAVAILABLE, "fixture missing"));
        when(ruleProvider.plan(any())).thenReturn(resp("rule-plan"));
        assertThatThrownBy(() -> chain.plan(request()))
                .isInstanceOf(PlannerException.class)
                .hasMessageContaining("PLANNER_FIXTURE_STRICT_FAIL");
        verify(modelProvider, times(1)).plan(any()); // 关键断言: 确定性失败零重试
        verify(ruleProvider, never()).plan(any()); // 关键断言: 评测隔离, 不降级 Rule
    }

    @Test
    @DisplayName("rule-fallback-enabled=false → Model 耗尽即抛, 不触碰 Rule (回退直败语义)")
    void ruleFallbackDisabled() {
        props.setRuleFallbackEnabled(false);
        when(modelProvider.plan(any()))
                .thenThrow(new PlannerException(PlannerException.Reason.PROVIDER_ERROR, "x"));
        assertThatThrownBy(() -> chain.plan(request()))
                .isInstanceOf(PlannerException.class)
                .hasMessageContaining("ALL_PLANNERS_FAILED");
        verify(ruleProvider, never()).plan(any());
    }

    @Test
    @DisplayName("Metrics 缺失 (null) → 降级链仍正常工作 (日志是最低保证)")
    void metricsOptional() {
        FallbackPlannerProvider noMetrics =
                new FallbackPlannerProvider(modelProvider, ruleProvider, props, null);
        when(modelProvider.plan(any()))
                .thenThrow(new PlannerException(PlannerException.Reason.TIMEOUT, "t"));
        when(ruleProvider.plan(any())).thenReturn(resp("rule-plan"));
        assertThat(noMetrics.plan(request()).planId()).isEqualTo("rule-plan");
    }
}
