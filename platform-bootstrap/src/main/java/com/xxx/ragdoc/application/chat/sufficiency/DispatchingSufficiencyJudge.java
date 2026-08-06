package com.xxx.ragdoc.application.chat.sufficiency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * PR-7b / EMS-PR7 §6: Rule 优先 + Model fallback 调度器。
 *
 * <p>规则:
 *
 * <ol>
 *   <li>永远先跑 {@link RuleSufficiencyJudge}; Rule 可判定 → 直接返回
 *   <li>Rule 返回 UNDETERMINED 且 {@link SufficiencyProperties#isModelFallbackEnabled()}=true
 *       → 跑 {@link ModelSufficiencyJudge}; Model 失败 → 保守 UNDETERMINED → REFUSE_NO_EVIDENCE
 *   <li>Rule 返回 UNDETERMINED 且 fallback=false → 保持 UNDETERMINED (Pipeline 拒答)
 *   <li>{@code properties.enabled=false} → 直接返回 UNDETERMINED (Pipeline 不会被 Trigger)
 * </ol>
 *
 * <p><b>False Sufficient 防护</b>: Model 返回 SUFFICIENT 时, RequirementCoverage ctor + Model Judge
 * 内已校验 `COVERED` 必含至少 1 个真实 evidenceId; 此处不再二次校验。
 *
 * <p>本调度器是 Spring Bean 入口; 所有 Pipeline 调用都走这一层 (而非直接 Rule/Model)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchingSufficiencyJudge implements EvidenceSufficiencyJudge {

    private final RuleSufficiencyJudge ruleJudge;
    private final ModelSufficiencyJudge modelJudge;
    private final SufficiencyProperties properties;

    @Override
    public SufficiencyDecision evaluate(SufficiencyRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        if (!properties.isEnabled()) {
            // Sufficiency 未启用 — 保守 UNDETERMINED, Pipeline 视情况拒答或默认 SUFFICIENT (PR-7c 决策)
            return SufficiencyDecision.rule(
                    SufficiencyStatus.UNDETERMINED, List.of(), List.of(), List.of(),
                    RecommendedAction.REFUSE_NO_EVIDENCE,
                    "SUFFICIENCY_DISABLED");
        }
        SufficiencyDecision ruleResult = ruleJudge.evaluate(request);
        if (ruleResult.status() != SufficiencyStatus.UNDETERMINED) {
            return ruleResult;
        }
        if (!properties.isModelFallbackEnabled()) {
            log.info("sufficiency.undetermined_no_fallback run={} reason={}",
                    request.runId(), ruleResult.reasonCode());
            return ruleResult;
        }
        log.info("sufficiency.model_fallback run={} ruleReason={}",
                request.runId(), ruleResult.reasonCode());
        return modelJudge.evaluate(request);
    }
}
