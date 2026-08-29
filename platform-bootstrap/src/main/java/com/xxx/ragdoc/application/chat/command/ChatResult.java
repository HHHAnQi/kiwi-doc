package com.xxx.ragdoc.application.chat.command;

import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;

/**
 * Chat 用例出参。Controller 转 DTO 返回给客户端。
 *
 * <p>所有调用(成功/降级)都用此结果表达, 避免走异常路径造成 200 body schema 二义性。
 */
public record ChatResult(
        String answer,
        List<Citation> citations,
        StateHint stateHint,
        TraceId traceId,
        /** Task 7: 引用核验结果, null=未启用 verifier (默认 disabled)。前端/评测 可读 outcome + min_score。 */
        VerificationResult verification,
        /**
         * PR-1 / EMS-PR1: 本次 chat 实际使用的证据三段快照。null = NO_RECALL/EMPTY_KB/未启用 / Operate-on-demand。
         * 暴露与否由 {@link com.xxx.ragdoc.interfaces.rest.dto.ChatResponse} + {@code
         * EvidenceDebugProperties} 在 Controller 出口处统一把关, 普通 200 响应不会序列化此字段。
         */
        EvidenceSnapshot evidenceSnapshot,
        /**
         * PR-7f.2c-pre: 本次 chat 实际命中的 PipelineType (由 Orchestrator 在出参处附加)。 null = 未填充 (兼容旧
         * pipeline 直接 new ChatResult 的路径)。 评测 Runner Adapter 据此判断 PLANNED_AGENT 是否真实生效。
         */
        PipelineType pipelineType,
        /** 在线主链稳定原因码；旧调用方未设置时由 OnlineExecutionKernel 在出口补齐。 */
        String reasonCode) {

    /**
     * Citation 元素(简化版, 与 api-contracts.md §D1 对齐)。 V1 chat 永远 citations=空, 因不调召回。
     *
     * <p>{@code llmContext} 是真正喂给 LLM 的完整上下文(parent-child 模式=parent 全文, flat=child 自身); Controller
     * 透传给 ChatResponse.Citation 供 RAGAS 评测与调试使用。前端可忽略。
     *
     * <p>{@code sectionPath}(Q3-B): 该 citation 所属 chunk 的 markdown heading 路径栈, 给前端/用户做章节级溯源; 空
     * list = 无 heading 上下文。
     *
     * <p>{@code verifyScore} (Task 7): 该 citation 的 NLI 支持分数 [0,1], null=未做核验。
     */
    public record Citation(
            Long chunkId,
            Long docId,
            int page,
            String snippet,
            String llmContext,
            List<String> sectionPath,
            Double verifyScore) {

        /** 老 6 字段构造器兼容 (verifyScore=null)。 */
        public Citation(
                Long chunkId,
                Long docId,
                int page,
                String snippet,
                String llmContext,
                List<String> sectionPath) {
            this(chunkId, docId, page, snippet, llmContext, sectionPath, null);
        }
    }

    /** Task 7 前 4 字段兼容构造 (verification=null)。 */
    public static ChatResult of(
            String answer, List<Citation> citations, StateHint hint, TraceId traceId) {
        return new ChatResult(answer, citations, hint, traceId, null, null, null, null);
    }

    /** V1 短命令: 空 citations + 无 verification。 */
    public static ChatResult of(StateHint hint, String answer, TraceId traceId) {
        return new ChatResult(answer, List.of(), hint, traceId, null, null, null, null);
    }

    /** PR-1: Task7 前 5 字段兼容 (evidenceSnapshot=null, 不破坏既有 callers)。 */
    public ChatResult(
            String answer,
            List<Citation> citations,
            StateHint stateHint,
            TraceId traceId,
            VerificationResult verification) {
        this(answer, citations, stateHint, traceId, verification, null, null, null);
    }

    /** PR-1: EvidenceSnapshot 接线后 6 字段兼容 (pipelineType=null)。 */
    public ChatResult(
            String answer,
            List<Citation> citations,
            StateHint stateHint,
            TraceId traceId,
            VerificationResult verification,
            EvidenceSnapshot evidenceSnapshot) {
        this(answer, citations, stateHint, traceId, verification, evidenceSnapshot, null, null);
    }

    /** reasonCode 接线前的七字段兼容构造。 */
    public ChatResult(
            String answer,
            List<Citation> citations,
            StateHint stateHint,
            TraceId traceId,
            VerificationResult verification,
            EvidenceSnapshot evidenceSnapshot,
            PipelineType pipelineType) {
        this(
                answer,
                citations,
                stateHint,
                traceId,
                verification,
                evidenceSnapshot,
                pipelineType,
                null);
    }

    /**
     * PR-7f.2c-pre: 在不重写其它字段的前提下, 给一份已有 ChatResult 附加 pipelineType。 Orchestrator 在
     * pipeline.execute(...) 之后调用此方法, 把 ctx.effectivePipeline() 透传给 Controller / 评测 Runner
     * Adapter。Pipeline 内部构造的 ChatResult 不必感知此字段。
     */
    public ChatResult withPipelineType(PipelineType pipelineType) {
        if (pipelineType == null) return this;
        return new ChatResult(
                this.answer,
                this.citations,
                this.stateHint,
                this.traceId,
                this.verification,
                this.evidenceSnapshot,
                pipelineType,
                this.reasonCode);
    }

    public ChatResult withReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.equals(this.reasonCode))
            return this;
        return new ChatResult(
                answer,
                citations,
                stateHint,
                traceId,
                verification,
                evidenceSnapshot,
                pipelineType,
                reasonCode);
    }
}
