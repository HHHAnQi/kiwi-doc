package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6 / EMS-PR6 §4.2: 单 Step 状态。Step 在 AgentRunExecutor 内串行执行时维护;OSS = Step पेंड loser-state。
 */
public enum AgentStepStatus {
    PENDING,
    RESERVED,
    RUNNING,
    SUCCEEDED,
    EMPTY,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    PERMISSION_DENIED,
    TIMED_OUT,
    CANCELLED,
    SKIPPED_DUPLICATE,
    SKIPPED_BUDGET;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == EMPTY
                || this == FAILED_TERMINAL
                || this == PERMISSION_DENIED
                || this == TIMED_OUT
                || this == CANCELLED
                || this == SKIPPED_DUPLICATE
                || this == SKIPPED_BUDGET;
    }

    public boolean isSuccessfulLike() {
        // SUCCEEDED/EMPTY 都允许 Executor 继续; FAILED_RETRYABLE 在 PR-6 不自动重试, 视为
        // 停止 (但 isTerminal 返回 false - 由 Executor 显式转 FAILED_TERMINAL)
        return this == SUCCEEDED || this == EMPTY;
    }
}
