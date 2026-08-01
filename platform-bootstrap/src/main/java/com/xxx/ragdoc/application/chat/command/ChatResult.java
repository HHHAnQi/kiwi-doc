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
    /**
     * Citation 元素(简化版, 与 api-contracts.md §D1 对齐)。 V1 chat 永远 citations=空, 因不调召回。
     *
     * <p>{@code llmContext} 是真正喂给 LLM 的完整上下文(parent-child 模式=parent 全文, flat=child 自身); Controller
     * 透传给 ChatResponse.Citation 供 RAGAS 评测与调试使用。前端可忽略。
     *
     * <p>{@code sectionPath}(Q3-B): 该 citation 所属 chunk 的 markdown heading 路径栈, 给前端/用户做章节级溯源; 空
     * list = 无 heading 上下文。
     */
    public record Citation(
            Long chunkId,
            Long docId,
            int page,
            String snippet,
            String llmContext,
            List<String> sectionPath) {}

    public static ChatResult of(StateHint hint, String answer, TraceId traceId) {
        return new ChatResult(answer, List.of(), hint, traceId);
    }
}
