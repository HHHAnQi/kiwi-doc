package com.xxx.ragdoc.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * chat 接口请求 DTO(与 api-contracts.md §D1 对齐)。
 *
 * <p>V3: source/version/language 为可选业务元数据过滤条件, 任一非空即限定向量检索范围(逻辑 AND)。
 *
 * <p>字段兼容: 项目全局 Jackson 命名策略为 SNAKE_CASE, 外部 JSON 必须传 {@code doc_id}/{@code top_k}/{@code
 * corrected_answer} 等 snake_case 键。为防止调用方误传 camelCase 静默丢字段(实测曾 导致 docId 过滤看似失效), 通过
 * {@code @JsonAlias} 同时接受 camelCase 别名。响应仍按 snake_case 输出。
 */
@Schema(name = "ChatRequest")
public record ChatRequest(
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
    public ChatRequest(String query, Long docId, Integer topK) {
        this(query, docId, topK, null, null, null);
    }
}
