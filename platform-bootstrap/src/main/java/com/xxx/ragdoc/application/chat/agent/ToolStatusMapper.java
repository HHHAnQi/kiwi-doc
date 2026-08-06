package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.tool.ToolStatus;

/**
 * PR-6b.3 / EMS-PR6 §7.4: {@link ToolStatus} → {@link AgentStepStatus} 确定性映射。
 *
 * <p>映射表 (table-driven):
 *
 * <table>
 * <tr><th>ToolStatus<th>AgentStepStatus
 * <tr><td>SUCCESS (有 Evidence)<td>SUCCEEDED
 * <tr><td>SUCCESS (无 Evidence) / EMPTY_RESULT<td>EMPTY
 * <tr><td>INVALID_ARGUMENT<td>FAILED_TERMINAL
 * <tr><td>PERMISSION_DENIED<td>PERMISSION_DENIED
 * <tr><td>TIMEOUT<td>TIMED_OUT
 * <tr><td>DEPENDENCY_UNAVAILABLE<td>FAILED_TERMINAL (执行力策略不自动重试,
 *      PR-6b 直接收敛 FAILED_RETRYABLE → FAILED_TERMINAL via {@link #postConverge})
 * <tr><td>RETRYABLE_ERROR<td>FAILED_TERMINAL (同上, PR-6b 不重试)
 * <tr><td>TERMINAL_ERROR<td>FAILED_TERMINAL
 * <tr><td>CANCELLED<td>CANCELLED
 * </table>
 *
 * <p>UNKNOWN 状态直接 fail-closed 转 FAILED_TERMINAL。
 *
 * <p>语义"RUNNING → FAILED_RETRYABLE → FAILED_TERMINAL"两步收敛在 Executor 内完成;
 * 这里只为单一 Tool 调用返回结果 — 当前 PR 不自动重试, 直接给 FAILED_TERMINAL。
 */
public final class ToolStatusMapper {

    private ToolStatusMapper() {}

    /** ToolStatus → StepStatus 映射 (不含 SUCCEEDED 与 EMPTY 区分, 因需要 holder 看 evidence count)。 */
    public static AgentStepStatus toStepStatus(ToolStatus toolStatus, boolean hasEvidence) {
        if (toolStatus == null) {
            return AgentStepStatus.FAILED_TERMINAL;
        }
        return switch (toolStatus) {
            case SUCCESS -> hasEvidence ? AgentStepStatus.SUCCEEDED : AgentStepStatus.EMPTY;
            case EMPTY_RESULT -> AgentStepStatus.EMPTY;
            case INVALID_ARGUMENT -> AgentStepStatus.FAILED_TERMINAL;
            case PERMISSION_DENIED -> AgentStepStatus.PERMISSION_DENIED;
            case TIMEOUT -> AgentStepStatus.TIMED_OUT;
            case DEPENDENCY_UNAVAILABLE, RETRYABLE_ERROR -> AgentStepStatus.FAILED_TERMINAL;
            case TERMINAL_ERROR -> AgentStepStatus.FAILED_TERMINAL;
            case CANCELLED -> AgentStepStatus.CANCELLED;
        };
    }

    /** errorCode 取 ToolError.safeMessage 简短化; null → ""。 */
    public static String errorCode(ToolStatus status) {
        return status == null ? "UNKNOWN_TOOL_STATUS" : status.name();
    }
}
