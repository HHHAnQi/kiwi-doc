package com.xxx.ragdoc.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检索评测用的直接召回请求 DTO。
 *
 * <p>与 {@link ChatRequest} 同字段(无 conversation_id), 但不接入 LLM。仅给 Retrieval Evaluation
 * Framework 调用 — 让离线评测直接拿到 RetrieveService 原始 {@code score} 算 MRR/NDCG, 而无需付费/等待
 * LLM, 也避免 {@code ChatResult.Citation} 把 score 丢弃后只能用 rank 近似。
 *
 * <p>字段兼容: 项目全局 Jackson SNAKE_CASE, snake_case 键为主, {@code @JsonAlias} 接受 camelCase 别名。
 */
@Schema(name = "RetrieveRequest")
public record RetrieveRequest(
        @Schema(description = "用户问题, 1-500 字", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "query 不能为空")
                @Size(max = 500, message = "query 长度不能超过 500")
                String query,
        @JsonAlias("docId") @Schema(description = "限定文档 ID, 可选; 不传=跨全库") Long docId,
        @JsonAlias("topK")
                @Schema(description = "召回 top_k, 默认 5, 范围 [1, 20]")
                @Min(value = 1, message = "top_k 最小 1")
                @Max(value = 20, message = "top_k 最大 20")
                Integer topK,
        @Schema(description = "限定来源组件(dubbo/nacos/seata/rocketmq/sentinel), 可选") String source,
        @Schema(description = "限定版本, 可选") String version,
        @Schema(description = "限定语言(zh/en), 可选") String language) {

    /** 老调用方兼容构造(无元数据过滤)。 */
    public RetrieveRequest(String query, Long docId, Integer topK) {
        this(query, docId, topK, null, null, null);
    }
}
