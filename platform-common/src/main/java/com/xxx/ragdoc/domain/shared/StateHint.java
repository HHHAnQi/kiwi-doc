package com.xxx.ragdoc.domain.shared;

/**
 * chat 响应的业务状态协议(替代 httpStatus=200 的伪错误码)。
 *
 * <p>设计依据见 ADR(第 12 轮补缺): 业务降级不是异常,是 ChatResponse 的正常字段。 ChatService 在 LLM 失败或召回为空时,直接 return 带
 * stateHint 的 ChatResponse, 禁止抛异常走 GlobalExceptionHandler。
 *
 * <p>决策优先级(不可打乱):
 *
 * <pre>
 *   EMPTY_KB       (0 个 READY 文档,前置短路)
 *   &gt; DOC_NOT_READY (409,走异常路径, 非 state_hint)
 *   &gt; NO_RECALL     (召回为空或全低于阈值)
 *   &gt; LLM_DEGRADED  (已召回但 LLM 失败)
 *   &gt; VERIFY_FAILED (Task 7: 已生成但未通过 citation 核验)
 *   &gt; OK
 * </pre>
 *
 * <p>与 feedbacks / chat_traces 表的 state_hint 列字符串值严格对齐。
 */
public enum StateHint {
    OK("正常问答"),
    REFUSED("安全策略或能力边界拒答"),
    EMPTY_KB("知识库为空"),
    NO_RECALL("召回为空或全低于阈值"),
    LLM_DEGRADED("LLM 调用失败,降级返回"),
    /**
     * Task 7: 已生成答案, 但 {@code CitationVerifier} NLI 核验 FAIL (answer 未被 evidence 支持)。
     *
     * <p>表征: 已有召回 + LLM 出答案 + verifier 拒答。区别于 {@link #LLM_DEGRADED} (LLM 调用本身失败)。
     */
    VERIFY_FAILED("引用核验未通过,拒答");

    private final String description;

    StateHint(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
