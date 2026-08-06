package com.xxx.ragdoc.application.chat.comparison;

import java.util.Map;

/**
 * PR-6c / EMS-PR6c §5.2: 单一比较对象。
 *
 * <p>两路比较固定为 <b>left + right</b> 两个 {@link ComparisonTarget}; 多于两个目标当前<b>不</b>自动扩展为 N 路比较。
 *
 * <p>来源: {@code RouterDecision.entities} 或服务端 {@code QueryNormalizer} 的结构化抽取;
 * <b>不允许</b> 客户端直接提交 Tool 参数 / 过滤字段 / tenantId。
 *
 * <p>{@link #normalizedValue()} 用于 "两实体规范化后相同 → 拒绝" 校验 (§5.2 规则 2)。
 *
 * <p>{@code filters} (非必填) 让 ComparisonPlanFactory 在 metadata_search 的方案下选择
 * version/product/source 字段; 没有时走 semantic_search。
 */
public record ComparisonTarget(
        String label,
        String normalizedValue,
        Map<String, Object> filters) {

    public ComparisonTarget {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("ComparisonTarget.label 必填");
        }
        if (normalizedValue == null || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("ComparisonTarget.normalizedValue 必填");
        }
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    /** 简化构造不带 filters (将走 semantic_search)。 */
    public static ComparisonTarget of(String label, String normalizedValue) {
        return new ComparisonTarget(label, normalizedValue, Map.of());
    }
}
