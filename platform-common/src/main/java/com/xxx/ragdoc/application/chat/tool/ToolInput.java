package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: 所有 Tool input 必须实现的标记接口。强制每个 Tool 用独立 typed record 作为输入, 不允许公共接口用 {@code
 * Map<String,Object>} 逃避契约。
 *
 * <p>实现方记录 (record) 应:
 *
 * <ul>
 *   <li>用 jakarta.validation 注解声明约束 (NotBlank/Min/Max/Pattern 等); ToolExecutor 在执行前 Bean Validate
 *   <li><b>禁止</b>携带 tenantId / userId / roles / tenantOverride / adminOverride 等身份字段 — 由
 *       ToolExecutionContext 注入, 不接受 LLM/客户端偷传。Executor 会显式拒绝此类字段名出现.
 * </ul>
 */
public interface ToolInput {
    /** 输入规范化字符串 (用作 dedup hash 一部分)。要求实现方把所有影响命中的字段按字典序拼起来, 不同顺序参数应 产出相同 normalized 字符串。 */
    default String normalizedForDedup() {
        return toString();
    }
}
