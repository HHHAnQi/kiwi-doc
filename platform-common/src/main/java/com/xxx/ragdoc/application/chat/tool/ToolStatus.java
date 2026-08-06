package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Agent Tool 调用状态。强制多状态细化, 禁止仅用 SUCCESS/FAILED 二元表达。
 *
 * <ul>
 *   <li>{@link #SUCCESS} — Tool 成功执行并产出可用的 Evidence / Verification / 其他结构化结果
 *   <li>{@link #EMPTY_RESULT} — Tool 执行成功但匹配为零 (无召回 / 文档不存在); 与 DEPENDENCY_UNAVAILABLE 严格区分
 *   <li>{@link #INVALID_ARGUMENT} — 输入违反 schema (空 query / topK 越界 / 非法枚举); 不可重试除非客户端改参
 *   <li>{@link #PERMISSION_DENIED} — 当前 Principal / tenant 在 ACL 校验阶段被拒 (deny-by-default)
 *   <li>{@link #TIMEOUT} — 触达 deadline; 与 DEPENDENCY_UNAVAILABLE 区分 (可能是对方慢而非不可达)
 *   <li>{@link #DEPENDENCY_UNAVAILABLE} — 下游 (Milvus / Embedding / MySQL / 验证 LLM) 真失败; 不应伪装成 EMPTY
 *   <li>{@link #RETRYABLE_ERROR} — 短暂网络/熔断打开; 客户端 / Executor 可重试
 *   <li>{@link #TERMINAL_ERROR} — 不可恢复错误 (代码 bug / 配置缺失); 不重试
 *   <li>{@link #CANCELLED} — 请求被取消传播 (上游 cancel); 不写 SUCCESS
 * </ul>
 *
 * <p>映射到 ToolError 时 {@link ToolStatus#isSuccessLike()} 决定是否缓存 / 进 trace "ok" bucket。
 */
public enum ToolStatus {
    SUCCESS,
    EMPTY_RESULT,
    INVALID_ARGUMENT,
    PERMISSION_DENIED,
    TIMEOUT,
    DEPENDENCY_UNAVAILABLE,
    RETRYABLE_ERROR,
    TERMINAL_ERROR,
    CANCELLED;

    /** 成功类状态: SUCCESS 或 EMPTY_RESULT (都代表 tool 本身没失败, 可缓存可重用)。 */
    public boolean isSuccessLike() {
        return this == SUCCESS || this == EMPTY_RESULT;
    }

    /** 应该写 SUCCESS trace flag 还是 failure; PERMISSION_DENIED / TIMEOUT / 各 ERROR 都是 false。 */
    public boolean isOkTraceFlag() {
        return this == SUCCESS || this == EMPTY_RESULT;
    }

    /** 按 EMS-PR4 §10 — 这类 status 默认可以命中调用去重 cache (按 runId+args+scope+indexVersion)。 */
    public boolean cacheable() {
        return this == SUCCESS || this == EMPTY_RESULT || this == PERMISSION_DENIED;
    }

    /** 是否需要对客户端/Executor 重试。 */
    public boolean retryable() {
        return this == TIMEOUT || this == RETRYABLE_ERROR || this == DEPENDENCY_UNAVAILABLE;
    }
}
