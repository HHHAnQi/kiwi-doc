package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6 / EMS-PR6 §4.1: Agent Run 全局状态机 (含 6 终态)。
 *
 * <p>合法主线转换: RECEIVED → ROUTED → PLANNED → EXECUTING → READY_TO_ANSWER → ANSWERED
 *
 * <p>失败转换:
 *
 * <ul>
 *   <li>任意非终态 → REFUSED_PERMISSION / BUDGET_EXCEEDED / TOOL_FAILED / TIMED_OUT / CANCELLED /
 *       SYSTEM_FAILED
 *   <li>READY_TO_ANSWER 也可 → REFUSED_NO_EVIDENCE / REFUSED_CONFLICT (本 PR 主要走 NO_EVIDENCE)
 * </ul>
 *
 * <p>终态不在 AgentStateMachine 中转换 (transit 时 fail-closed)
 */
public enum AgentRunStatus {
    RECEIVED,
    ROUTED,
    PLANNED,
    EXECUTING,
    READY_TO_ANSWER,

    ANSWERED,
    REFUSED_NO_EVIDENCE,
    REFUSED_PERMISSION,
    REFUSED_CONFLICT,
    BUDGET_EXCEEDED,
    TOOL_FAILED,
    TIMED_OUT,
    CANCELLED,
    SYSTEM_FAILED;

    public boolean isTerminal() {
        return this == ANSWERED
                || this == REFUSED_NO_EVIDENCE
                || this == REFUSED_PERMISSION
                || this == REFUSED_CONFLICT
                || this == BUDGET_EXCEEDED
                || this == TOOL_FAILED
                || this == TIMED_OUT
                || this == CANCELLED
                || this == SYSTEM_FAILED;
    }
}
