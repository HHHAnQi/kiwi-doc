package com.xxx.ragdoc.application.chat.sufficiency;

/**
 * PR-7b / EMS-PR7 §6.2: Sufficiency 判定的整体状态。
 *
 * <p>{@link EvidenceSufficiencyJudge} 输出此状态 + 一组 {@link RequirementCoverage}。
 *
 * <ul>
 *   <li>{@link #SUFFICIENT} — 所有 required Requirement 已 COVERED, 没有 CONFLICTED → 进入 Answer Composer
 *   <li>{@link #INSUFFICIENT} — 至少一个 required Requirement NOT_COVERED / PARTIALLY_COVERED, 无
 *       CONFLICTED; 若 Replan 允许 → 触发 Replan, 否则 REFUSE_NO_EVIDENCE
 *   <li>{@link #CONFLICTED} — 出现 CONFLICTED Coverage → REFUSE_CONFLICT (不允许 Replan 掩盖)
 *   <li>{@link #UNDETERMINED} — Rule 无法判定 + Model fallback 关闭, 或 Model 输出非法被代码拒绝; 默认保守处理为
 *       INSUFFICIENT (拒答而非生成虚假 SUFFICIENT)
 * </ul>
 *
 * <p>False Sufficient 是<b>最严重错误</b> (Revision §6.6): Sufficiency 误判 SUFFICIENT 会让 Answer Composer 在
 * Evidence 不足时生成虚假答案。代码层硬约束: COVERED <b>必须</b>有 ≥1 evidenceId (见 RequirementCoverage ctor)。
 */
public enum SufficiencyStatus {
    SUFFICIENT,
    /** 部分覆盖: ≥1 个需求有证据但非全部 — 带 Composer 标注回答, 不拒答。 */
    PARTIAL,
    INSUFFICIENT,
    CONFLICTED,
    UNDETERMINED
}
