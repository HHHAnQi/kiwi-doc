package com.xxx.ragdoc.application.chat.router;

import java.util.List;
import java.util.Map;

/**
 * PR-3 / EMS-PR3: 一次 Router 决策的不可变快照。
 *
 * <p>Router 一轮决策即返回此结果; <b>不允许</b> Router 在 Evidence 不足时被 Orchestrator 二次调用
 * (动态 Replan 是 PR-7 Planner 阶段的职责)。Router 低置信的回退由 {@link #strategy()} 直接体现
 * 为 {@link ExecutionStrategy#CLASSIC_RAG}, {@link #reasonCode()} 标 LOW_CONFIDENCE_FALLBACK。
 *
 * <h2>字段语义</h2>
 *
 * <ul>
 *   <li>{@link #intent} — {@link TaskIntent}, 仅描述题目类型, 不绑定 strategy
 *   <li>{@link #strategy} — 实际执行的 {@link ExecutionStrategy}; strategy 与 intent 一致性由
 *       Router 内部映射 + 低置信回退规则共同决定
 *   <li>{@link #entities} — 从 query 里抽出的命名实体 (产品名 / 版本 / 错误码 / 时间 / 对象 A/B);
 *       RouterDecision 不验证实体 ACL, ACL 由 Pipeline 内 RetrieveService / Tool 守门
 *   <li>{@link #filters} — 元数据过滤提示 (source / version / docId 等); Pipeline 可选用
 *   <li>{@link #confidence} ∈ [0,1] — Router 对本次判断的置信度; < 0.7 由 Router 自己转为 CLASSIC_RAG 回退
 *   <li>{@link #reasonCode} — 短字符串, 用于 Trace / 日志, 例如 VERSION_CHANGELOG / TWO_VERSION_COMPARE
 *       / LOW_CONFIDENCE_FALLBACK / OUT_OF_SCOPE_ACTION
 * </ul>
 *
 * <p>不可变 record; {@link #entities()} / {@link #filters()} 经 List.copyOf / Map.copyOf 保护。
 */
public record RouterDecision(
        TaskIntent intent,
        ExecutionStrategy strategy,
        List<String> entities,
        Map<String, Object> filters,
        double confidence,
        String reasonCode) {

    public RouterDecision {
        if (intent == null) {
            throw new IllegalArgumentException("RouterDecision.intent 必填");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("RouterDecision.strategy 必填");
        }
        entities = entities == null ? List.of() : List.copyOf(entities);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("RouterDecision.reasonCode 必填");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("RouterDecision.confidence 必须在 [0,1]");
        }
    }

    /** 占位 UNANSWERABLE 决策工厂; 用于 Router 决定 REFUSE 的快捷构造。 */
    public static RouterDecision refuse(String reasonCode, double confidence) {
        return new RouterDecision(
                TaskIntent.UNANSWERABLE,
                ExecutionStrategy.REFUSE,
                List.of(),
                Map.of(),
                confidence,
                reasonCode);
    }
}
