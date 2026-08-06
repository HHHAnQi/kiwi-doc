package com.xxx.ragdoc.application.chat.planner;

import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.AgentToolStep;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.agent.PlanValidationResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7a / EMS-PR7 §4.6: 把 {@link PlannerResponse} 转换为 {@link DeterministicExecutionPlan},
 * 并<b>在 PlanValidator 之前</b>执行 Planner 专属校验 (Revision §4.6)。
 *
 * <p>额外校验:
 *
 * <ol>
 *   <li>{@code targetedRequirementIds} 必须存在于 PlannerRequest.requirements
 *   <li>{@code step.requirementIds} 必须存在于 PlannerRequest.requirements
 *   <li>stepId 在 Plan 内唯一, 且与 {@code completedSteps} 不冲突 (replan 不复用既有 stepId)
 *   <li>不允许重复 Tool signature (与 completedSteps.signature 比对)
 *   <li>Step 数不超过 {@code remainingBudget.remainingSteps} 与 PlannerProperties.maxPlanSteps
 *   <li>banned identity 字段保护由 AgentToolStep 内置 (stepId banned 词扫描)
 * </ol>
 *
 * <p>任一不满足 → 返回 invalid, Pipeline 决策 SYSTEM_FAILED / REFUSE; 不允许部分 Step 执行。
 *
 * <p><b>不可变</b>: 不修改 PlannerRequest.requirements / completedSteps; 装配结果 deterministic。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerPlanAssembler {

    private final com.xxx.ragdoc.application.chat.agent.PlanValidator planValidator;
    private final PlannerProperties properties;

    /**
     * @param request 原 PlannerRequest
     * @param response Provider 返回的 PlannerResponse
     * @param policy 服务端 AgentExecutionPolicy (allowedTools / budget; 服务端构造, 不接受 client override)
     */
    public AssemblyResult assemble(
            PlannerRequest request, PlannerResponse response, AgentExecutionPolicy policy) {
        if (request == null || response == null || policy == null) {
            return AssemblyResult.invalid("NULL_INPUT");
        }
        // 校验 1: targetedRequirementIds / step.requirementIds 存在
        Set<String> knownReq = new HashSet<>();
        for (EvidenceRequirement r : request.requirements()) knownReq.add(r.requirementId());

        for (String rid : response.targetedRequirementIds()) {
            if (!knownReq.contains(rid)) {
                return AssemblyResult.invalid("UNKNOWN_TARGETED_REQUIREMENT: " + rid);
            }
        }

        Set<String> seenStepIds = new HashSet<>();
        Set<String> seenToolSignatures = new HashSet<>();
        // Replan: 已完成 step 都加上
        for (CompletedStepSummary s : request.completedSteps()) {
            seenStepIds.add(s.stepId());
            seenToolSignatures.add(s.toolSignatureHash());
        }

        if (response.steps().isEmpty()) {
            return AssemblyResult.invalid("PLAN_HAS_NO_STEPS");
        }

        // 校验 step 数 ≤ min(maxPlanSteps, remainingSteps)
        int cap = Math.min(properties.getMaxPlanSteps(),
                request.remainingBudget() == null ? properties.getMaxPlanSteps()
                        : request.remainingBudget().remainingSteps());
        if (response.steps().size() > cap) {
            return AssemblyResult.invalid("PLAN_TOO_MANY_STEPS: " + response.steps().size() + ">" + cap);
        }

        List<AgentToolStep> agentSteps = new ArrayList<>();
        for (PlannedToolStep s : response.steps()) {
            if (!seenStepIds.add(s.stepId())) {
                return AssemblyResult.invalid("DUPLICATE_STEP_ID: " + s.stepId());
            }
            // Tool signature dedup (Revision §4.6 rule 8)
            String sig = signatureOf(s);
            if (!seenToolSignatures.add(sig)) {
                return AssemblyResult.invalid("PLAN_REPEATED_TOOL_CALL: " + s.stepId());
            }
            for (String rid : s.requirementIds()) {
                if (!knownReq.contains(rid)) {
                    return AssemblyResult.invalid("STEP_REFERENCES_UNKNOWN_REQUIREMENT: "
                            + s.stepId() + "/" + rid);
                }
            }
            // AgentToolStep 内置 banned 字段扫描 (覆盖 stepId 敏感词)
            AgentToolStep agentStep;
            try {
                agentStep = new AgentToolStep(
                        s.stepId(), s.toolName(), s.toolVersion(),
                        s.input(), s.dependsOn(), s.expectedEvidence() == null
                                ? "" : s.expectedEvidence(),
                        s.required());
            } catch (IllegalArgumentException ex) {
                return AssemblyResult.invalid("STEP_VALIDATION_FAILED: "
                        + s.stepId() + ": " + ex.getMessage());
            }
            agentSteps.add(agentStep);
        }

        DeterministicExecutionPlan plan = new DeterministicExecutionPlan(
                response.planId(), response.planVersion(), agentSteps);

        // 跑现有 PlanValidator (Allowlist / 拓扑 / banned 字段 final check)
        PlanValidationResult validation = planValidator.validate(plan, policy);
        if (!validation.valid()) {
            return AssemblyResult.invalid("PLAN_VALIDATOR_FAILED: "
                    + validation.errors().stream()
                            .map(PlanValidationResult.PlanValidationError::safeMessage)
                            .toList());
        }
        return AssemblyResult.ok(plan, response.targetedRequirementIds(), response.reasonCode());
    }

    private static String signatureOf(PlannedToolStep s) {
        return s.toolName() + "|" + s.toolVersion() + "|"
                + (s.input() == null ? "" : s.input().normalizedForDedup());
    }

    /** Assembler 结果 (ok 时含 plan + targeted requirements + reasonCode 给 trace)。 */
    public record AssemblyResult(
            boolean valid,
            String invalidReason,
            DeterministicExecutionPlan plan,
            List<String> targetedRequirementIds,
            String reasonCode) {

        public static AssemblyResult invalid(String reason) {
            return new AssemblyResult(false, reason, null, List.of(), "");
        }

        public static AssemblyResult ok(
                DeterministicExecutionPlan plan, List<String> targetedReqIds, String reasonCode) {
            return new AssemblyResult(true, null, plan, List.copyOf(targetedReqIds), reasonCode);
        }
    }
}
