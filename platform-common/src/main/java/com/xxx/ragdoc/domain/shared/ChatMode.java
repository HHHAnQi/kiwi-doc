package com.xxx.ragdoc.domain.shared;

/**
 * PR-2 / EMS-PR2: 用户在 chat 请求里显式声明的执行模式。
 *
 * <p>语义:
 *
 * <ul>
 *   <li>{@link #RAG} — 强制 Classic RAG, 不进 Agent / Router / 任何新链路
 *   <li>{@link #AGENTIC} — 强制 Agentic RAG; 仅测试/评测/授权用户使用。 PR-2 中 Agentic Pipeline 尚未实现 → 显式返回
 *       {@code 422 AGENTIC_MODE_UNAVAILABLE}, 不静默降级为 Classic
 *   <li>{@link #AUTO} — 由 Router 决定执行策略; PR-2 中 Router 未实现 → 暂时回退 Classic RAG
 * </ul>
 *
 * <p>请求体里 {@code mode} 字段缺失 → 默认 {@link #AUTO} (老客户端兼容)。未知值 → 400 SYS_INVALID_ARGUMENT (Jackson
 * 反序列化枚举失败 → MethodArgumentNotValidException / InvalidFormatException → GlobalExceptionHandler 转结构化
 * 400)。
 *
 * <p>客户端 <b>不能</b> 通过 mode 修改 tenantId / userId / ACL / 是否为管理员。mode 只决定 pipeline 选择。
 */
public enum ChatMode {
    RAG,
    AGENTIC,
    AUTO
}
