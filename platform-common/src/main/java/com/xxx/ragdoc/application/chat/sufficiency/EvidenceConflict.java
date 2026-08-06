package com.xxx.ragdoc.application.chat.sufficiency;

import java.util.List;

/**
 * PR-7b / EMS-PR7 §6.x: 证据冲突项 (两条以上 Evidence 对同一 Requirement 提供互相矛盾的事实)。
 *
 * <p>{@link ConflictType#VERSION_VALUE_MISMATCH} 是最常见的确定性冲突 (例: 同字段在两版本返回不同值;
 * Rule Judge 显式检测)。
 *
 * <p>Sufficiency={@link SufficiencyStatus#CONFLICTED} 时必须列出至少一条 EvidenceConflict。
 */
public record EvidenceConflict(
        String requirementId,
        ConflictType type,
        List<String> evidenceIds /* 互相冲突的至少 2 条 */,
        String safeReason) {

    public enum ConflictType {
        VERSION_VALUE_MISMATCH,
        ENTITY_ATTRIBUTE_MISMATCH,
        TEMPORAL_ORDER_INVALID,
        MODEL_DETECTED_SEMANTIC_CONFLICT
    }

    public EvidenceConflict {
        if (requirementId == null || requirementId.isBlank()) {
            throw new IllegalArgumentException("EvidenceConflict.requirementId 必填");
        }
        if (type == null) type = ConflictType.MODEL_DETECTED_SEMANTIC_CONFLICT;
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (safeReason == null) safeReason = "";
        // 不变量: 至少 2 条 Evidence 才算冲突
        if (evidenceIds.size() < 2 && type != ConflictType.MODEL_DETECTED_SEMANTIC_CONFLICT) {
            throw new IllegalArgumentException(
                    "EvidenceConflict 至少需要 2 个 evidenceId (req=" + requirementId + ")");
        }
    }
}
