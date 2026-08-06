package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Tool 执行错误的结构化载体。
 *
 * <p><b>安全硬约束</b>: {@link #safeMessage()} <b>必须</b> 对普通用户/Planner 安全, 不得包含:
 *
 * <ul>
 *   <li>认证 Token
 *   <li>数据库连接串 / 内部 host
 *   <li>原始堆栈 (stack trace)
 *   <li>无权文档 / chunk 名称 (反枚举)
 *   <li>敏感原文 (PII / 内部业务数据)
 * </ul>
 *
 * <p>{@link #dependency()} 是结构化的下游名称 (e.g. "milvus", "embedding", "mysql", "verification-llm"),
 * 用于 Metrics / Trace 不暴露具体 host。
 *
 * <p>{@link #error_code()} 使用项目既有 {@link com.xxx.ragdoc.common.exception.ErrorCode} 之一 (PR-4 会在
 * 该枚举新增 TOOL_* 系列); safeMessage 是 fallback 文字。
 */
public record ToolError(
        String errorCode,
        String safeMessage,
        String dependency,
        boolean retryable) {

    public ToolError {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("ToolError.errorCode 必填");
        }
        if (safeMessage == null) {
            safeMessage = ""; // 允许空 (有些架构倾向不向 Planner 流消息字面量)
        }
        if (dependency == null) {
            dependency = ""; // 自身错误无 external dependency
        }
    }

    /** 工厂: PERMISSION_DENIED 类错误, 不带 dependency。 */
    public static ToolError of(String code, String safeMessage) {
        return new ToolError(code, safeMessage, "", false);
    }

    /** 工厂: 依赖故障, 含 dependency 名 + retryable 标志。 */
    public static ToolError dependencyError(
            String code, String safeMessage, String dependency, boolean retryable) {
        return new ToolError(code, safeMessage, dependency, retryable);
    }
}
