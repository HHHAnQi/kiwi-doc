package com.xxx.ragdoc.application.chat.planner;

import com.xxx.ragdoc.application.metrics.MetricsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * P0-1(降级链): Planner 运行时降级链 — Model → retry → RuleTemplate。
 *
 * <p>修复前: Model/Rule 按 {@code model-enabled} 启动期互斥装配, Model 一次失败即
 * INITIAL_PLANNER_FAILED 直败 (对比: Sufficiency 已有 DispatchingSufficiencyJudge 运行时
 * fallback)。本类固定持有 bean 名 {@code basePlannerProvider}, 是
 * {@link HarnessAwarePlannerProvider} 装饰器的底层委托。
 *
 * <p>降级语义:
 *
 * <ol>
 *   <li>{@code model-enabled=false} (ModelPlannerProvider bean 不存在) → 纯转发 Rule,
 *       行为与旧互斥装配 zero-diff
 *   <li>Model 失败 → 重试 {@link PlannerProperties#getModelRetryAttempts()} 次 (默认 1);
 *       {@code FIXTURE_*} 确定性失败<b>不</b>重试也<b>不</b>降级 Rule — REPLAY 评测语义:
 *       夹具缺失即严格失败, 降级链不得静默污染实验组 (P0-2 评测隔离防线)
 *   <li>重试耗尽且 {@code rule-fallback-enabled=true} → Rule 生成, response.reasonCode 标记
 *       {@link #REASON_RULE_FALLBACK}:REASON:attN (coordinator 据此把 agent_run.plannerVersion
 *       写为 rule-fallback-v1:REASON, 评测 runner 逐样本可辨降级来源)
 *   <li>Rule 也失败/返回 null (无 allowed tool) → 抛 {@link PlannerException}
 *       (PROVIDER_ERROR, ALL_PLANNERS_FAILED) — 由 Pipeline 层降级 Classic (见
 *       PlannedAgentPipeline)
 * </ol>
 *
 * <p>每次降级: 结构化日志 + {@code ragdoc.agent.planner_degradation_total{stage}} 指标,
 * 无 silent fallback。不触碰 bounded loop / 状态机 CAS / lease / 幂等键 — 降级发生在
 * run 创建之前 (prepare 第 2 步), 不产生孤儿 run。
 */
@Slf4j
@Component("basePlannerProvider")
public class FallbackPlannerProvider implements PlannerProvider {

    /** PlannerResponse.reasonCode 标记前缀: 本链路 Model 重试耗尽后由 Rule 兜底生成。 */
    public static final String REASON_RULE_FALLBACK = "RULE_FALLBACK_AFTER_MODEL_FAILURE";

    /** PlannerResponse.reasonCode 标记前缀: Model 非首次尝试成功 (仍是 MODEL 来源, 但需可追溯)。 */
    public static final String REASON_MODEL_RETRY = "MODEL_RETRY_SUCCESS";

    /** MetricsPort 缺失 (单测/最小装配) 时的空实现。 */
    private static final MetricsPort NO_METRICS =
            new MetricsPort() {
                @Override
                public void recordChatTotal(long durationMs, String outcome) {}

                @Override
                public void recordChatFirstToken(long latencyMs) {}

                @Override
                public void incrementLlmCall(String route) {}

                @Override
                public void recordRetrieveRecall(int count) {}

                @Override
                public void recordRerankLatency(long durationMs, boolean success) {}

                @Override
                public void recordRetrieveTotal(long durationMs) {}

                @Override
                public void recordRewriteLatency(long durationMs, String outcome) {}

                @Override
                public void incrementTopicShift(String detected) {}

                @Override
                public void incrementCompression(String outcome) {}

                @Override
                public void incrementHistoryForceTruncate() {}

                @Override
                public void recordTokens(
                        int promptTokens, int completionTokens, String route, String model) {}
            };

    private final PlannerProvider modelProvider; // nullable: model-enabled=false 时无 bean
    private final RuleTemplatePlannerProvider ruleProvider;
    private final PlannerProperties properties;
    private final MetricsPort metrics;

    // 多构造器必须显式指定装配用例(否则 Spring 找 default ctor 失败)
    @org.springframework.beans.factory.annotation.Autowired
    public FallbackPlannerProvider(
            ObjectProvider<ModelPlannerProvider> modelProvider,
            RuleTemplatePlannerProvider ruleProvider,
            PlannerProperties properties,
            ObjectProvider<MetricsPort> metrics) {
        this(
                modelProvider.getIfAvailable(),
                ruleProvider,
                properties,
                metrics.getIfAvailable(() -> NO_METRICS));
    }

    FallbackPlannerProvider(
            PlannerProvider model,
            RuleTemplatePlannerProvider ruleProvider,
            PlannerProperties properties,
            MetricsPort metrics) {
        this.modelProvider = model;
        this.ruleProvider = ruleProvider;
        this.properties = properties;
        this.metrics = metrics == null ? NO_METRICS : metrics;
    }

    @Override
    public PlannerResponse plan(PlannerRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        if (modelProvider == null) {
            return ruleProvider.plan(request); // model-enabled=false: zero-diff 转发
        }

        RuntimeException lastModelFailure = null;
        String lastModelFailureReason = "UNKNOWN";
        int attempts = 1 + Math.max(0, properties.getModelRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                PlannerResponse resp = modelProvider.plan(request);
                if (resp == null) throw new PlannerException(
                        PlannerException.Reason.PROVIDER_ERROR,
                        "model planner returned null run=" + request.runId());
                if (attempt > 1) {
                    log.info(
                            "planner.model_retry_success run={} attempt={}/{}",
                            request.runId(),
                            attempt,
                            attempts);
                    metrics.incrementPlannerDegradation("model_retry_success");
                    // 逐样本可追溯: planner 仍为 MODEL, 但 response 标记重试次数
                    return withMarker(resp, REASON_MODEL_RETRY + ":att" + attempt);
                }
                return resp;
            } catch (RuntimeException ex) {
                lastModelFailure = ex;
                lastModelFailureReason = failureReasonCode(ex);
                log.warn(
                        "planner.model_attempt_failed run={} attempt={}/{} err={}",
                        request.runId(),
                        attempt,
                        attempts,
                        ex.toString());
                if (isDeterministicFailure(ex)) {
                    // P0-2(评测隔离): REPLAY 夹具缺失/冲突 = 评测环境错误, 严格失败 —
                    // 不重试也<b>不</b>降级 Rule, 防止降级链静默污染实验组。
                    throw asPlannerException(
                            "PLANNER_FIXTURE_STRICT_FAIL run=" + request.runId(), ex);
                }
            }
        }

        if (!properties.isRuleFallbackEnabled()) {
            throw asPlannerException("ALL_PLANNERS_FAILED run=" + request.runId(), lastModelFailure);
        }
        try {
            PlannerResponse ruleResp = ruleProvider.plan(request);
            if (ruleResp != null) {
                log.warn(
                        "planner.rule_fallback run={} modelErr={} ruleSteps={} rulePlanId={}",
                        request.runId(),
                        String.valueOf(lastModelFailure),
                        ruleResp.steps().size(),
                        ruleResp.planId());
                metrics.incrementPlannerDegradation("rule_fallback");
                // 逐样本可追溯: 来源=Rule 兜底 + 失败原因 + Model 尝试次数
                return withMarker(
                        ruleResp,
                        REASON_RULE_FALLBACK + ":" + lastModelFailureReason + ":att" + attempts);
            }
            log.warn("planner.rule_fallback_empty run={}", request.runId());
        } catch (RuntimeException ex) {
            log.warn("planner.rule_fallback_failed run={} err={}", request.runId(), ex.toString());
        }
        throw asPlannerException(
                "ALL_PLANNERS_FAILED run=" + request.runId() + " ruleExhausted", lastModelFailure);
    }

    private static PlannerResponse withMarker(PlannerResponse resp, String marker) {
        return new PlannerResponse(
                resp.planId(),
                resp.planVersion(),
                resp.steps(),
                resp.targetedRequirementIds(),
                marker);
    }

    private static String failureReasonCode(RuntimeException ex) {
        return ex instanceof PlannerException pe ? pe.reason.name() : "RUNTIME";
    }

    private static boolean isDeterministicFailure(RuntimeException ex) {
        return ex instanceof PlannerException pe
                && (pe.reason == PlannerException.Reason.FIXTURE_UNAVAILABLE
                        || pe.reason == PlannerException.Reason.FIXTURE_CONFLICT);
    }

    private static PlannerException asPlannerException(String message, RuntimeException cause) {
        if (cause instanceof PlannerException pe) {
            return new PlannerException(pe.reason, message + " last=" + cause.getMessage(), cause);
        }
        return new PlannerException(
                PlannerException.Reason.PROVIDER_ERROR, message + " last=" + cause, cause);
    }
}
