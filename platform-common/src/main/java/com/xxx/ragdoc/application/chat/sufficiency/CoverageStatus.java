package com.xxx.ragdoc.application.chat.sufficiency;

/**
 * PR-7b / EMS-PR7 §6.3: 单 Requirement 的覆盖等级。
 *
 * <p>由 {@code EvidenceSufficiencyJudge} 计算每个 Requirement 的 CoverageStatus, 汇总为
 * {@link SufficiencyStatus}。
 *
 * <p>{@code COVERED} 必须有至少一条已授权 Evidence 关联; {@code CONFLICTED} 表示两条以上 Evidence
 * but 版本/事实互相矛盾。
 */
public enum CoverageStatus {
    /** 明确由至少一条 Evidence 满足。 */
    COVERED,
    /** 部分满足 — 仅在 Rule Judge 无法判定 fully covered 时使用, 默认等同 NOT_COVERED (保守)。 */
    PARTIALLY_COVERED,
    /** 没有任何授权 Evidence 命中。 */
    NOT_COVERED,
    /** 多 Evidence 互相矛盾 (例: 两版本字段值不一致)。 */
    CONFLICTED;

    /** 容错 valueOf: 输入 unknown → NOT_COVERED (保守, 不允许模型创建新状态)。 */
    public static CoverageStatus valueOfSafe(String s) {
        if (s == null) return NOT_COVERED;
        try {
            return CoverageStatus.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NOT_COVERED;
        }
    }
}
