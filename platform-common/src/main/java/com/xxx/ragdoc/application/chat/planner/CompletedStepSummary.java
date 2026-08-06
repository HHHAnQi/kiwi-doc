package com.xxx.ragdoc.application.chat.planner;

import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2 重规划输入: 已完成 Step 的安全摘要。
 *
 * <p><b>不</b>含: Tool 调情的请求/响应原文, internal exception, Principal, Evidence 正文; 只给 Planner
 * "Step A 用 semantic_search 拿到 2 条证据, 命中 R1" 这种最小可参考信息。
 */
public record CompletedStepSummary(
        String stepId,
        String toolName,
        String toolVersion,
        /** SHA-256 of (toolName|version|normalizedInput|scope|indexVersion); PR-7c loop detect 用。 */
        String toolSignatureHash,
        int evidenceCount,
        /** 本 Step 携带的 requirementId (Planner 决定)。 */
        java.util.List<String> targetedRequirementIds,
        /** "SUCCEEDED" / "EMPTY" / "FAILED_TERMINAL" 等; 用字符串避免泄漏 AgentStepStatus enum。 */
        String outcome,
        Map<String, Object> safeMetadata) {

    public CompletedStepSummary {
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName");
        if (toolVersion == null || toolVersion.isBlank()) toolVersion = "v1";
        if (toolSignatureHash == null || toolSignatureHash.isBlank()) {
            throw new IllegalArgumentException("toolSignatureHash");
        }
        if (outcome == null) outcome = "UNKNOWN";
        targetedRequirementIds =
                targetedRequirementIds == null ? java.util.List.of() : java.util.List.copyOf(targetedRequirementIds);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
    }
}
