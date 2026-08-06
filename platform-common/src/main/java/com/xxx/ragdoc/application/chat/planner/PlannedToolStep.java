package com.xxx.ragdoc.application.chat.planner;

import com.xxx.ragdoc.application.chat.tool.ToolInput;
import java.util.List;

/**
 * PR-7a / EMS-PR7 §4.4: Planner 单条 Step。
 *
 * <p>与 {@link com.xxx.ragdoc.application.chat.agent.AgentToolStep} 的区别:
 *
 * <ul>
 *   <li>Planner Step 携带 {@link #requirementIds} (Planner 显式声明本 Step 服务的 Requirement)
 *   <li>Planner 不构造最终 Plan; PlannerResponse → DeterministicExecutionPlan 由 {@code PlannerPlanAssembler} 完成
 * </ul>
 *
 * <p>Planner <b>不能</b>设置 {@code toolName} 之外的元数据 (tenant/version override), 一切由 PlanValidator
 * 与 Assembler 在服务端补齐。
 */
public record PlannedToolStep(
        String stepId,
        String toolName,
        String toolVersion,
        ToolInput input,
        List<String> dependsOn,
        List<String> requirementIds,
        String expectedEvidence,
        boolean required) {

    public PlannedToolStep {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("PlannedToolStep.stepId 必填");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("PlannedToolStep.toolName 必填");
        }
        if (toolVersion == null || toolVersion.isBlank()) toolVersion = "v1";
        if (input == null) {
            throw new IllegalArgumentException("PlannedToolStep.input 必填");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
        if (expectedEvidence == null) expectedEvidence = "";
    }
}
