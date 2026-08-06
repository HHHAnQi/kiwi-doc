package com.xxx.ragdoc.application.chat.agent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PR-6b / EMS-PR6 §5: 单 Step 状态机 (显式合法转换表 + 终态保护)。
 *
 * <p>与 {@link AgentStateMachine} (Run 级) 刻意分两套 ——
 * <ul>
 *   <li>Step 主线: PENDING → RESERVED → RUNNING → SUCCEEDED / EMPTY
 *   <li>Step 失败收敛: RUNNING → FAILED_RETRYABLE → FAILED_TERMINAL (PR-6b 不自动重试)
 *   <li>Step 跳过: PENDING → SKIPPED_BUDGET; PENDING/RESERVED → SKIPPED_DUPLICATE
 *   <li>Step 取消: PENDING/RESERVED/RUNNING → CANCELLED
 *   <li>依赖失败 (无独立 SKIPPED_DEPENDENCY 状态, EMS-PR6 §5.1): 转 FAILED_TERMINAL +
 *       errorCode=DEPENDENCY_NOT_SATISFIED (终态, 不调 Tool)
 * </ul>
 *
 * <p>不变量: 终态不允许任何 outbound transition; 违反抛 {@link IllegalStepTransitionException} fail-closed。
 * Repository Adapter 不接受任意来源状态。
 */
public final class AgentStepStateMachine {

    private AgentStepStateMachine() {}

    private static final Map<AgentStepStatus, Set<AgentStepStatus>> TRANSITIONS = new HashMap<>();

    static {
        register(AgentStepStatus.PENDING,
                AgentStepStatus.RESERVED,
                AgentStepStatus.SKIPPED_BUDGET,
                AgentStepStatus.SKIPPED_DUPLICATE,
                AgentStepStatus.CANCELLED);
        register(AgentStepStatus.RESERVED,
                AgentStepStatus.RUNNING,
                AgentStepStatus.SKIPPED_DUPLICATE,
                AgentStepStatus.CANCELLED,
                AgentStepStatus.TIMED_OUT,
                AgentStepStatus.FAILED_TERMINAL);
        register(AgentStepStatus.RUNNING,
                AgentStepStatus.SUCCEEDED,
                AgentStepStatus.EMPTY,
                AgentStepStatus.FAILED_RETRYABLE,
                AgentStepStatus.FAILED_TERMINAL,
                AgentStepStatus.PERMISSION_DENIED,
                AgentStepStatus.TIMED_OUT,
                AgentStepStatus.CANCELLED);
        register(AgentStepStatus.FAILED_RETRYABLE,
                AgentStepStatus.FAILED_TERMINAL,
                AgentStepStatus.CANCELLED);
    }

    private static void register(AgentStepStatus from, AgentStepStatus... tos) {
        EnumSet<AgentStepStatus> set = EnumSet.noneOf(AgentStepStatus.class);
        for (AgentStepStatus to : tos) set.add(to);
        TRANSITIONS.put(from, set);
    }

    /** 校验转换合法性: 终态不允许任何出口; 非法源→目标抛 {@link IllegalStepTransitionException}。 */
    public static void checkLegal(AgentStepStatus from, AgentStepStatus to) {
        if (from == null) {
            throw new IllegalStepTransitionException(null, to, "from=null");
        }
        if (from.isTerminal()) {
            throw new IllegalStepTransitionException(from, to, "终态不允许再次转换");
        }
        Set<AgentStepStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStepTransitionException(from, to, "未在合法转换表");
        }
    }

    public static class IllegalStepTransitionException extends RuntimeException {
        public final AgentStepStatus from;
        public final AgentStepStatus to;

        public IllegalStepTransitionException(AgentStepStatus from, AgentStepStatus to, String reason) {
            super(from + " → " + to + " 非法: " + reason);
            this.from = from;
            this.to = to;
        }
    }
}
