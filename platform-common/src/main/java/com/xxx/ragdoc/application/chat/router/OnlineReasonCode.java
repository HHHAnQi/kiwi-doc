package com.xxx.ragdoc.application.chat.router;

/** 在线主链稳定原因码。禁止在安全、拒答和降级分支继续散落自由文本。 Router 的细粒度诊断码仍保留在 RouterDecision 中。 */
public enum OnlineReasonCode {
    ROUTER_DISABLED,
    RAG_MODE_FORCED,
    AGENTIC_MODE_FORCED,
    ROUTE_CHAT,
    ROUTE_RETRIEVE,
    ROUTE_TOOL,
    REFUSE_EMPTY_QUERY,
    REFUSE_PROMPT_INJECTION,
    REFUSE_OUT_OF_SCOPE,
    REFUSE_OUT_OF_DOMAIN,
    REFUSE_POLICY,
    ROUTER_EXCEPTION_FALLBACK,
    CONTEXT_TOKEN_BUDGET_APPLIED,
    EMPTY_KB,
    NO_RECALL,
    LLM_UNAVAILABLE,
    VERIFICATION_FAILED,
    TOOL_FAILED,
    CANCELLED,
    INTERNAL_ERROR;

    public static OnlineReasonCode from(RouterDecision decision) {
        if (decision == null) return INTERNAL_ERROR;
        if (decision.strategy() != ExecutionStrategy.REFUSE) {
            return switch (decision.strategy()) {
                case DIRECT_CHAT -> ROUTE_CHAT;
                case TOOL_EXECUTION -> ROUTE_TOOL;
                default -> ROUTE_RETRIEVE;
            };
        }
        String code = decision.reasonCode();
        if ("EMPTY_QUERY".equals(code)) return REFUSE_EMPTY_QUERY;
        if ("PROMPT_INJECTION_ATTEMPT".equals(code)) return REFUSE_PROMPT_INJECTION;
        if ("OUT_OF_SCOPE_ACTION".equals(code)) return REFUSE_OUT_OF_SCOPE;
        if ("OUT_OF_KB_DOMAIN".equals(code)) return REFUSE_OUT_OF_DOMAIN;
        return REFUSE_POLICY;
    }
}
