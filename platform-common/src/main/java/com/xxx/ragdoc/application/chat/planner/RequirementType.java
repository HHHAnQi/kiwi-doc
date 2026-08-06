package com.xxx.ragdoc.application.chat.planner;

/**
 * PR-7a / EMS-PR7 §4.3: Evidence Requirement 类型。Planner/Sufficiency 协同的稳定枚举。
 *
 * <p>RequirementType 决定 Sufficiency Judge 评估 Evidence 覆盖的方式, 但不影响 Tool 调度本身。
 */
public enum RequirementType {
    /** 直接事实 (例: "X 使用的协议名")。 */
    FACT,
    /** 单个 entity 的属性 (例: "v2.0 的发布日期")。 */
    ENTITY_ATTRIBUTE,
    /** 实体间关系 (例: "A 与 B 的依赖关系")。 */
    RELATION,
    /** 时间 / 版本时序 (例: "v1 → v2 之后")。 */
    TEMPORAL,
    /** 比较的某一侧 (例: "comparison:left = 产品 A 的认证机制")。 */
    COMPARISON_SIDE,
    /** 通过 follow-up entity 链接的多跳获取 (例: "实现 X 的组件名, 再查那组件 v2 变化")。 */
    FOLLOW_UP_ENTITY
}
