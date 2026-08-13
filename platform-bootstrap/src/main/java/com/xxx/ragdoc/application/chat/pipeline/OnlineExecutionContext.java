package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.router.OnlineReasonCode;
import com.xxx.ragdoc.application.chat.router.OnlineRoute;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.time.Instant;

/**
 * 同步与 SSE 共用的在线执行快照。安全身份、路由、预算和截止时间均由服务端一次生成。
 */
public record OnlineExecutionContext(
        String requestId,
        Principal principal,
        ChatMode requestedMode,
        OnlineRoute route,
        PipelineType effectivePipeline,
        TraceId traceId,
        ExecutionPolicy executionPolicy,
        RouterDecision routerDecision,
        OnlineReasonCode reasonCode,
        int contextTokenBudget,
        Instant deadline) {

    public OnlineExecutionContext {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId 必填");
        if (principal == null) throw new IllegalArgumentException("principal 必须来自 AuthContext");
        if (requestedMode == null) requestedMode = ChatMode.AUTO;
        if (route == null) throw new IllegalArgumentException("route 必填");
        if (traceId == null) throw new IllegalArgumentException("traceId 必填");
        if (executionPolicy == null) executionPolicy = ExecutionPolicy.defaults();
        if (routerDecision == null) throw new IllegalArgumentException("routerDecision 必填");
        if (reasonCode == null) reasonCode = OnlineReasonCode.INTERNAL_ERROR;
        if (contextTokenBudget <= 0) contextTokenBudget = 3000;
        if (deadline == null) deadline = Instant.now().plusSeconds(60);
        if (route == OnlineRoute.REFUSE && effectivePipeline != null) {
            throw new IllegalArgumentException("REFUSE 是终态，不允许绑定 pipeline");
        }
        if (route != OnlineRoute.REFUSE && effectivePipeline == null) {
            throw new IllegalArgumentException("非 REFUSE 路由必须绑定 pipeline");
        }
    }

    public ChatExecutionContext toLegacyContext() {
        if (effectivePipeline == null) throw new IllegalStateException("REFUSE 无 legacy pipeline context");
        return new ChatExecutionContext(
                requestId, principal, requestedMode, effectivePipeline, traceId, executionPolicy, routerDecision);
    }
}
