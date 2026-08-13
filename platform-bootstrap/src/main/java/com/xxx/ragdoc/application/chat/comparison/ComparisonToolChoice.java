package com.xxx.ragdoc.application.chat.comparison;

/**
 * PR-6c / EMS-PR6c §5.3: ComparisonStep 选定的 Tool + 它的输入描述。
 *
 * <p>将 planStep 的高层意图 (semantic_search vs metadata_search) 映射到具体 Tool 名 + version + 规范化 input
 * 提示。ComparisonPlanFactory 内部消费, AgentRunFactory 调 PlanValidator 之前转成 {@code AgentToolStep}。
 */
public record ComparisonToolChoice(
        String toolName,
        String toolVersion /* "v1" */,
        /** 用于 trace 与 Prompt; 不含敏感原文 (会被 evidenceId 关联)。 */
        String humanDescription) {

    public ComparisonToolChoice {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName");
        if (toolVersion == null || toolVersion.isBlank()) toolVersion = "v1";
        if (humanDescription == null) humanDescription = "";
    }

    public static ComparisonToolChoice semanticSearchV1(String description) {
        return new ComparisonToolChoice("semantic_search", "v1", description);
    }

    public static ComparisonToolChoice metadataSearchV1(String description) {
        return new ComparisonToolChoice("metadata_search", "v1", description);
    }

    public static ComparisonToolChoice keywordSearchV1(String description) {
        return new ComparisonToolChoice("keyword_search", "v1", description);
    }
}
