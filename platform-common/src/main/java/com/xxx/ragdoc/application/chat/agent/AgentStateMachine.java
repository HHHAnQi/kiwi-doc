package com.xxx.ragdoc.application.chat.agent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PR-6 / EMS-PR6 §5: Agent Run 状态机 (显式合法转换表 + 终态保护)。
 *
 * <ul>
 *   <li>主线: RECEIVED → ROUTED → PLANNED → EXECUTING → READY_TO_ANSWER → ANSWERED
 *   <li>READY_TO_ANSWER 也可 → REFUSED_NO_EVIDENCE / REFUSED_CONFLICT (本 PR REFUSED_NO_EVIDENCE)
 *   <li>任意非终态 → BUDGET_EXCEEDED / TOOL_FAILED / TIMED_OUT / CANCELLED / SYSTEM_FAILED
 *   <li>非终态 → REFUSED_PERMISSION
 *   <li>终态不再允许任何 outbound transition
 * </ul>
 *
 * <p>{@link #checkLegal(AgentRunStatus, AgentRunStatus)} 抛 {@link IllegalTransitionException} 失败关闭。
 */
public final class AgentStateMachine {

    private AgentStateMachine() {}

    private static final Map<AgentRunStatus, Set<AgentRunStatus>> TRANSITIONS = new HashMap<>();

    static {
        // 主线 + 失败转换组合写入
        register(AgentRunStatus.RECEIVED, AgentRunStatus.ROUTED);
        register(AgentRunStatus.ROUTED, AgentRunStatus.PLANNED);
        register(AgentRunStatus.PLANNED, AgentRunStatus.EXECUTING);
        register(AgentRunStatus.EXECUTING, AgentRunStatus.READY_TO_ANSWER);
        register(
                AgentRunStatus.READY_TO_ANSWER,
                AgentRunStatus.ANSWERED,
                AgentRunStatus.REFUSED_NO_EVIDENCE,
                AgentRunStatus.REFUSED_CONFLICT);

        // 通用失败转换: 任意非终态可转到这些失败终态
        AgentRunStatus[] failures = {
            AgentRunStatus.REFUSED_PERMISSION,
            AgentRunStatus.BUDGET_EXCEEDED,
            AgentRunStatus.TOOL_FAILED,
            AgentRunStatus.TIMED_OUT,
            AgentRunStatus.CANCELLED,
            AgentRunStatus.SYSTEM_FAILED
        };
        for (AgentRunStatus s :
                new AgentRunStatus[] {
                    AgentRunStatus.RECEIVED, AgentRunStatus.ROUTED, AgentRunStatus.PLANNED,
                    AgentRunStatus.EXECUTING, AgentRunStatus.READY_TO_ANSWER
                }) {
            for (AgentRunStatus f : failures) {
                TRANSITIONS.computeIfAbsent(s, k -> java.util.EnumSet.noneOf(AgentRunStatus.class)).add(f);
            }
        }
    }

    private static void register(AgentRunStatus from, AgentRunStatus... tos) {
        java.util.EnumSet<AgentRunStatus> set = java.util.EnumSet.noneOf(AgentRunStatus.class);
        for (AgentRunStatus to : tos) set.add(to);
        TRANSITIONS.put(from, set);
    }

    /** 校验转换合法性: 终态不允许任何出口; 非法源→目标抛 IllegalTransitionException。 */
    public static void checkLegal(AgentRunStatus from, AgentRunStatus to) {
        if (from == null) {
            throw new IllegalTransitionException(null, to, "from=null");
        }
        if (from.isTerminal()) {
            throw new IllegalTransitionException(from, to, "终态不允许再次转换");
        }
        Set<AgentRunStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalTransitionException(from, to, "未在合法转换表");
        }
    }

    public static class IllegalTransitionException extends RuntimeException {
        public final AgentRunStatus from;
        public final AgentRunStatus to;

        public IllegalTransitionException(AgentRunStatus from, AgentRunStatus to, String reason) {
            super(from + " → " + to + " 非法: " + reason);
            this.from = from;
            this.to = to;
        }
    }
}
