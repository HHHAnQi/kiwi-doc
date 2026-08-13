package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.tool.ToolInput;
import java.util.List;

/**
 * PR-6 / EMS-PR6 §4.6: 单个 Tool 步骤。Executor 按依赖顺序串行执行;required Step 失败 → Run 终态。
 *
 * <p>禁止字段集: stepId 含 banned 名 (不接收 tenantId/userId/rawToken 等)；input 禁带 tenantId 字段 (ToolExecutor
 * 守门)。
 */
public record AgentToolStep(
        String stepId,
        String toolName,
        String toolVersion,
        ToolInput input,
        List<String> dependsOn,
        String expectedEvidence,
        boolean required) {

    public AgentToolStep {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("AgentToolStep.stepId 必填");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("AgentToolStep.toolName 必填");
        }
        if (toolVersion == null || toolVersion.isBlank()) toolVersion = "v1";
        if (input == null) {
            throw new IllegalArgumentException("AgentToolStep.input 必填 (typed ToolInput record)");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        // stepId 不能含 banned 名 (防恶意 Step 引入 identity 字段)
        String lc = stepId.toLowerCase();
        if (lc.contains("tenantid") || lc.contains("userid") || lc.contains("token")) {
            throw new IllegalArgumentException("AgentToolStep.stepId 含敏感词: " + stepId);
        }
        if (expectedEvidence == null) expectedEvidence = "";
    }
}
