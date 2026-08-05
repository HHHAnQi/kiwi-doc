package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * chat 接口响应 DTO。 契约见 docs/features/api-contracts.md §D1。
 *
 * <p>所有路径(成功 / 业务降级)统一用 200 + 此结构, body schema 单一, 客户端易处理。
 *
 * <p>PR-1 / EMS-PR1: 不再回传内部 EvidenceSnapshot, 除非 {@code includeEvidence=true} (调试总闸 +
 * 请求头双控)。普通响应只发安全 Citation。
 */
@Schema(name = "ChatResponse")
public record ChatResponse(
        @Schema(description = "答案正文(可能是真实答案或兜底文案)") String answer,
        @Schema(description = "引用列表(V1 永远为空数组)") List<Citation> citations,
        @Schema(description = "业务状态, OK/EMPTY_KB/NO_RECALL/LLM_DEGRADED", example = "EMPTY_KB")
                String stateHint,
        @Schema(description = "用于 feedback 反馈关联") String traceId,
        @Schema(description = "PR-1: 真实 Evidence 三段快照(仅调试开启时存在; 普通响应不会出现此字段)")
                EvidenceSnapshot evidence) {
    /** 默认转换: 不带 evidence, 与历史客户端 4 字段响应兼容。 */
    public static ChatResponse from(ChatResult r) {
        return from(r, false);
    }

    /**
     * PR-1: 显式控制是否暴露 evidence。caller (ChatController) 在 {@code rag.evidence.debug-enabled=true}
     * 且请求带 {@code X-Debug-Evidence: true} 时传 true; 其它情况一律不暴露。
     */
    public static ChatResponse from(ChatResult r, boolean includeEvidence) {
        List<Citation> citations =
                r.citations().stream()
                        .map(
                                c ->
                                        new Citation(
                                                c.chunkId(),
                                                c.docId(),
                                                c.page(),
                                                c.snippet(),
                                                c.llmContext(),
                                                c.sectionPath()))
                        .toList();
        EvidenceSnapshot evidenceToExpose = includeEvidence ? r.evidenceSnapshot() : null;
        return new ChatResponse(
                r.answer(), citations, r.stateHint().name(), r.traceId().value(), evidenceToExpose);
    }

    /**
     * 引用单元。 {@code snippet} 是子切片(child chunk)摘要用于前端展示, {@code llmContext} 是真正喂给 LLM
     * 的完整上下文(parent-child 模式下=parent 全文 ~1500-2000字, flat 模式下=child 自身 ~400字)。
     *
     * <p>{@code llmContext} 仅供评测脚本(RAGAS)与调试使用, 前端如不需要请忽略。暴露它的根本原因是 P3-A parent-child 的核心价值就是拉长
     * llmContext; 若评测脚本只看 snippet(=child) 则数字无法捕捉提升。
     *
     * <p>{@code sectionPath}(Q3-B): 该 citation 所属 chunk 的 markdown heading 路径栈, 给前端/用户做章节级溯源; 空
     * list = 无 heading 上下文。
     */
    public record Citation(
            Long chunkId,
            Long docId,
            int page,
            String snippet,
            @Schema(description = "真正喂给 LLM 的完整上下文(评测用)") String llmContext,
            @Schema(description = "Q3-B: 章节 heading 路径栈") List<String> sectionPath) {}
}
