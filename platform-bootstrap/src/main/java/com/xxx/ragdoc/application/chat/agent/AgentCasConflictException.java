package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6b.1 / EMS-PR6 §11.2: 多 CAS 事务内任一 CAS 失败, 抛本异常让 Spring `@Transactional REQUIRES_NEW` 整体回滚 —
 * 不允许半提交 (Reservation CAS 成功但 Step CAS 失败, 或反向)。
 *
 * <p>Executor 捕获后:
 *
 * <ul>
 *   <li>读最新 Run/Step 状态 (重新进入 EXECUTING / 状态机合法态)
 *   <li>最多重试 {@code rag.agent.max-cas-retries} (默认 3) 次
 *   <li>终态 / 状态机非法 → 立即停止并转 Run SYSTEM_FAILED
 * </ul>
 *
 * <p><b>严禁</b>吞掉异常继续 step。
 */
public class AgentCasConflictException extends RuntimeException {

    /** 哪一侧的 CAS 失败 (便于诊断 trace)。 */
    public enum Side {
        RUN_RESERVATION,
        RUN_BUDGET_STATE,
        RUN_SETTLE,
        STEP_RESERVE,
        STEP_MARK_RUNNING,
        STEP_TERMINATE
    }

    public final Side side;

    public AgentCasConflictException(Side side, String message) {
        super("[" + (side == null ? Side.RUN_RESERVATION : side) + "] " + message);
        this.side = side == null ? Side.RUN_RESERVATION : side;
    }

    public AgentCasConflictException(Side side, String message, Throwable cause) {
        super("[" + (side == null ? Side.RUN_RESERVATION : side) + "] " + message, cause);
        this.side = side == null ? Side.RUN_RESERVATION : side;
    }
}
