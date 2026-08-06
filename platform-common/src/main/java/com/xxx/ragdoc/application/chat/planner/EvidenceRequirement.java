package com.xxx.ragdoc.application.chat.planner;

import java.util.List;
import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.3: 单条 EvidenceRequirement。
 *
 * <p>由 {@code RequirementExtractor} (PR-7c 引入) 在 Planner 之前生成; 给 Planner 提供"要证明什么"的
 * <b>稳定真值</b>, 同时给 {@code EvidenceSufficiencyJudge} (PR-7b) 提供 coverage 评估锚点。
 *
 * <p><b>不变量</b>:
 *
 * <ul>
 *   <li>{@code requirementId} stable + 全 Plan/Replan 全局唯一; Planner/Sufficiency 全程引用此 id
 *   <li>{@code required=true} 的 Requirement 若无任何授权 Evidence 覆盖 → Sufficiency=INSUFFICIENT → 拒答
 *   <li>{@code required=false} 视为可选澄清, 不参与 INSUFFICIENT 判定
 * </ul>
 *
 * <p>Planner <b>不能</b>修改 Requirement 内容; 只能引用 {@code requirementId}。
 */
public record EvidenceRequirement(
        String requirementId,
        String description,
        RequirementType type,
        boolean required,
        List<String> targetEntities,
        Map<String, Object> expectedFilters) {

    public EvidenceRequirement {
        if (requirementId == null || requirementId.isBlank()) {
            throw new IllegalArgumentException("EvidenceRequirement.requirementId 必填");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("EvidenceRequirement.description 必填");
        }
        if (type == null) {
            throw new IllegalArgumentException("EvidenceRequirement.type 必填");
        }
        targetEntities = targetEntities == null ? List.of() : List.copyOf(targetEntities);
        expectedFilters = expectedFilters == null ? Map.of() : Map.copyOf(expectedFilters);
    }

    /** 简化构造: 无 entity/filter 的 global fact。 */
    public static EvidenceRequirement fact(String id, String desc, boolean required) {
        return new EvidenceRequirement(id, desc, RequirementType.FACT, required, List.of(), Map.of());
    }
}
