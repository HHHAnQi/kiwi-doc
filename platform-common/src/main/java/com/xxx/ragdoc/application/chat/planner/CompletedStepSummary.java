package com.xxx.ragdoc.application.chat.planner;

import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2 重规划输入: 已完成 Step 的安全摘要。
 *
 * <p><b>不</b>含: Tool 调情的请求/响应原文, internal exception, Principal, Evidence 正文; 只给 Planner "Step A 用
 * semantic_search 拿到 2 条证据, 命中 R1" 这种最小可参考信息。
 */
public record CompletedStepSummary(
        String stepId,
        String toolName,
        String toolVersion,
        /**
         * SHA-256 of (toolName|version|normalizedInput|scope|indexVersion); PR-7c loop detect 用。
         */
        String toolSignatureHash,
        int evidenceCount,
        /** 本 Step 携带的 requirementId (Planner 决定)。 */
        java.util.List<String> targetedRequirementIds,
        /** "SUCCEEDED" / "EMPTY" / "FAILED_TERMINAL" 等; 用字符串避免泄漏 AgentStepStatus enum。 */
        String outcome,
        /**
         * P2-D2: 该 step 实际尝试的检索 query(截断≤80字符, 只含查询文本不含 payload)。
         * Replan prompt 据此让 LLM 生成<b>不同</b>的查询 — 签名 hash 对生成新 query 无语义价值。
         * 非 Search 型工具为空串。
         */
        String attemptedQuery,
        Map<String, Object> safeMetadata) {

    public CompletedStepSummary {
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName");
        if (toolVersion == null || toolVersion.isBlank()) toolVersion = "v1";
        if (toolSignatureHash == null || toolSignatureHash.isBlank()) {
            throw new IllegalArgumentException("toolSignatureHash");
        }
        if (outcome == null) outcome = "UNKNOWN";
        if (attemptedQuery == null) attemptedQuery = "";
        if (attemptedQuery.length() > 80) attemptedQuery = attemptedQuery.substring(0, 80);
        targetedRequirementIds =
                targetedRequirementIds == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(targetedRequirementIds);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
    }
}
