package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6b.1 / EMS-PR6 §11.1: Run + Steps 初始化阶段任一步失败 (创建 run、创建 step、三次接收态 CAS) 抛。
 *
 * <p>{@link AgentPersistenceCoordinator#initializeRunAndSteps} 是<b>单一</b> @Transactional REQUIRES_NEW
 * 短事务, 任一失败整体回滚 — 不允许遗留 RECEIVED / ROUTED / PLANNED 的中间 Run (Revision §1 §9)。
 *
 * <p>外层 Factory 捕获决定: 重试 / 转 SYSTEM_FAILED / 抛 5xx。
 */
public class AgentRunInitializationException extends RuntimeException {

    public final String runId;

    public AgentRunInitializationException(String runId, String message) {
        super(message);
        this.runId = runId;
    }

    public AgentRunInitializationException(String runId, String message, Throwable cause) {
        super(message, cause);
        this.runId = runId;
    }
}
