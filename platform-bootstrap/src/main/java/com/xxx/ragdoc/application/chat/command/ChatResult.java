package com.xxx.ragdoc.application.chat.command;

import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;

/**
 * Chat 用例出参。Controller 转 DTO 返回给客户端。
 *
 * <p>所有调用(成功/降级)都用此结果表达, 避免走异常路径造成 200 body schema 二义性。
 */
public record ChatResult(
        String answer, List<Citation> citations, StateHint stateHint, TraceId traceId) {
    /** Citation 元素(简化版, 与 api-contracts.md §D1 对齐)。 V1 chat 永远 citations=空, 因不调召回。 */
    public record Citation(Long chunkId, Long docId, int page, String snippet) {}

    public static ChatResult of(StateHint hint, String answer, TraceId traceId) {
        return new ChatResult(answer, List.of(), hint, traceId);
    }
}
