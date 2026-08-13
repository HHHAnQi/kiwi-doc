package com.xxx.ragdoc.application.chat.sufficiency;

/**
 * PR-7b / EMS-PR7 §6.2: Sufficiency 决策后的<b>建议动作</b>。Pipeline 按此路由下一步。
 *
 * <p>Revision §7.1 / §8.2 关联:
 *
 * <ul>
 *   <li>{@link #ANSWER} — SUFFICIENT → 调 Answer Composer (使用最终证据)
 *   <li>{@link #REPLAN} — INSUFFICIENT + replan 仍允许 → 调 Planner (replanIndex=1)
 *   <li>{@link #REFUSE_NO_EVIDENCE} — INSUFFICIENT + 无 Replan / UNDETERMINED 保守 → Run
 *       REFUSED_NO_EVIDENCE
 *   <li>{@link #REFUSE_CONFLICT} — CONFLICTED → Run REFUSED_CONFLICT (不掩盖冲突)
 * </ul>
 */
public enum RecommendedAction {
    ANSWER,
    REPLAN,
    REFUSE_NO_EVIDENCE,
    REFUSE_CONFLICT
}
