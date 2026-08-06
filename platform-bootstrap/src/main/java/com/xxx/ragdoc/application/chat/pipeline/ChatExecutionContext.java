package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.TraceId;

/**
 * PR-2 / EMS-PR2: 单次 chat 请求的不可变执行上下文 (同步与 SSE 共享)。
 *
 * <p>硬不变量 (跨 PR):
 *
 * <ul>
 *   <li>{@link Principal} 来自已鉴权 {@code AuthContext}, 不接受客户端传入。tenantId 只能从 Principal 派生。
 *   <li>单请求内不可变; 并发请求之间不串 requestId / Principal / mode / Trace。
 *   <li>不使用全局可变字段保存当前请求状态 — 必须经过本 context 传递。
 *   <li>{@link #requestedMode()} 是用户请求里的原始 mode; {@link #effectivePipeline()} 是 Orchestrator 实际派发
 *       的 pipeline type; 两者都进 Trace。
 * </ul>
 *
 * <p>{@link #executionPolicy()} 在 PR-2 只承载现有 timeout / 流式开关 / cancel signal 等,
 * 不引入任何 Agent Budget 字段 (留给后续 PR)。
 */
public record ChatExecutionContext(
        String requestId,
        Principal principal,
        ChatMode requestedMode,
        PipelineType effectivePipeline,
        TraceId traceId,
        ExecutionPolicy executionPolicy) {

    public ChatExecutionContext {
        if (principal == null) {
            throw new IllegalArgumentException("ChatExecutionContext.principal 必须来自 AuthContext");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("ChatExecutionContext.requestId 必填");
        }
        if (traceId == null) {
            throw new IllegalArgumentException("ChatExecutionContext.traceId 必填");
        }
        if (requestedMode == null) {
            // 规范化: null → AUTO (老客户端兼容, 见 ChatMode javadoc)
            requestedMode = ChatMode.AUTO;
        }
        if (effectivePipeline == null) {
            throw new IllegalArgumentException("ChatExecutionContext.effectivePipeline 必填");
        }
        executionPolicy = executionPolicy != null ? executionPolicy : ExecutionPolicy.defaults();
    }
}
