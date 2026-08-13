package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-7c / EMS-PR7 §2.2: 单次执行 Phase 如何收尾 Run。
 *
 * <ul>
 *   <li>{@link #KEEP_EXECUTING}: Phase 完成后 <b>不</b> 转 READY_TO_ANSWER / 终态; PlannedAgentPipeline 在
 *       Sufficiency 判断后由 Finalizer 决定终态 (Revision §2.1 状态机不变量)。
 *   <li>{@link #FINALIZE_AFTER_STEPS}: Phase 完成后转 READY_TO_ANSWER (或 REFUSED_NO_EVIDENCE), PR-6c
 *       ComparisonWorkflow / 单次 Plan 用此 (零回归)。
 * </ul>
 */
public enum ExecutionCompletionPolicy {
    KEEP_EXECUTING,
    FINALIZE_AFTER_STEPS
}
