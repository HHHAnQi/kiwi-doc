package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.RetrieveService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 检索评测用的直接召回响应 DTO。
 *
 * <p>与 {@link ChatResponse} 同形(不含 answer/state_hint), 但保留 {@code score} 字段 — {@code score} 在
 * ChatResult.Citation 阶段被剥离, 评测脚本无法经 /chat 算基于真实分数的 MRR/NDCG, 故单独定义直召回响应。
 *
 * <p>顶层附 {@code model_version}/{@code embedding_version}/{@code rerank_model}/{@code
 * rerank_enabled}, 让 eval_report.json 无需额外读 .env 即可记录 "这次结果是在什么模型栈下跑的" — 可复现性是本框架的目标。
 *
 * <p>{@code from()} 的 model/embedding 参数为纯 String, 而非 LlmProperties/EmbeddingProperties — 后者在
 * infrastructure 包下, interfaces 包依赖它违反 ArchUnit (见 ArchitectureTest#interfaces不直接访问Infrastructure)。
 */
@Schema(name = "RetrieveResponse")
public record RetrieveResponse(
        @Schema(description = "召回列表(按 score 降序)") List<Citation> items,
        @Schema(
                        description =
                                "rerank 分支: not_enabled / skipped_single_candidate / applied / failed / empty_fallback / skipped")
                String rerankState,
        @Schema(description = "第①段 hybrid 召回头部分数") float top1HybridScore,
        @Schema(description = "第③段 rerank 头部分数(未启用=0)") float top1RerankScore,
        @Schema(description = "本次 JVM 的 LLM 主模型, 用于评测报告记录可复现性") String modelVersion,
        @Schema(description = "embedding 模型 id(BGE-M3)") String embeddingVersion,
        @Schema(description = "reranker 模型 id") String rerankModel,
        @Schema(description = "rerank 是否启用") boolean rerankEnabled) {

    public static RetrieveResponse from(
            RetrieveService.RetrieveResult r,
            String llmModel,
            String embeddingModel,
            RerankProperties rerank) {
        List<Citation> items =
                r.items().stream()
                        .map(
                                c ->
                                        new Citation(
                                                c.chunkId(),
                                                c.docId(),
                                                c.page(),
                                                c.snippet(),
                                                c.llmContext(),
                                                c.score(),
                                                c.sectionPath()))
                        .toList();
        return new RetrieveResponse(
                items,
                r.rerankState(),
                r.top1HybridScore(),
                r.top1RerankScore(),
                llmModel == null ? "" : llmModel,
                embeddingModel == null ? "" : embeddingModel,
                rerank == null ? null : rerank.getModel(),
                rerank != null && rerank.isEnabled());
    }

    /**
     * 引用单元。与 {@code ChatResponse.Citation} 同形, 多一个 {@code score}: 当 {@code rerank_enabled=true} 时 是
     * cross-encoder relevance_score, 否则是 hybrid(dense+BM25 RRF) 分数。
     */
    public record Citation(
            Long chunkId,
            Long docId,
            int page,
            String snippet,
            @Schema(description = "真正喂给 LLM 的完整上下文(评测用)") String llmContext,
            @Schema(description = "本条召回分数, 用于 MRR/NDCG") float score,
            @Schema(description = "章节 heading 路径栈") List<String> sectionPath) {}
}
