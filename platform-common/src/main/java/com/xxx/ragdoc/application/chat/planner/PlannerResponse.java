package com.xxx.ragdoc.application.chat.planner;

import java.util.List;

/**
 * PR-7a / EMS-PR7 §4.4: Planner 严格结构化输出 (JSON-derived)。
 *
 * <p><b>严格不变量</b>:
 *
 * <ul>
 *   <li><b>不</b>含 Chain-of-Thought / 长 rationale; 只允许短枚举式 {@link #reasonCode}
 *   <li>{@link #steps} 全部必须通过 PlanValidator 才能转 DeterministicExecutionPlan
 *   <li>{@link #targetedRequirementIds} 必须存在 PlannerRequest.requirements 中; Assembler 验证
 * </ul>
 *
 * <p>reasonCode 枚举 (短代码, 便于 Trace / Metrics / Replay):
 *
 * <ul>
 *   <li>{@link #INITIAL_MULTI_HOP_PLAN}
 *   <li>{@link #FOLLOW_UP_ENTITY_SEARCH}
 *   <li>{@link #MISSING_REQUIREMENT_RECOVERY}
 * </ul>
 *
 * <p>Planner 不立即执行任何 Tool;Planner 输出是<b>建议</b>, 最终是否被 Assembler 使用仍需校验。
 */
public record PlannerResponse(
        String planId,
        String planVersion,
        List<PlannedToolStep> steps,
        List<String> targetedRequirementIds,
        String reasonCode) {

    /** 推荐的 reasonCode 短代码 (PlannerProvider 实现按表选用; Assembler 不强制校验具体值)。 */
    public static final String INITIAL_MULTI_HOP_PLAN = "INITIAL_MULTI_HOP_PLAN";
    public static final String FOLLOW_UP_ENTITY_SEARCH = "FOLLOW_UP_ENTITY_SEARCH";
    public static final String MISSING_REQUIREMENT_RECOVERY = "MISSING_REQUIREMENT_RECOVERY";

    public PlannerResponse {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("PlannerResponse.planId 必填");
        }
        if (planVersion == null || planVersion.isBlank()) planVersion = "v1";
        steps = steps == null ? List.of() : List.copyOf(steps);
        targetedRequirementIds =
                targetedRequirementIds == null ? List.of() : List.copyOf(targetedRequirementIds);
        if (reasonCode == null) reasonCode = "";
    }
}
