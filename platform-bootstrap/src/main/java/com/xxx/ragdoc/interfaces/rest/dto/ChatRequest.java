package com.xxx.ragdoc.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** chat 接口请求 DTO(与 api-contracts.md §D1 对齐)。 */
@Schema(name = "ChatRequest")
public record ChatRequest(
        @Schema(description = "用户问题, 1-500 字", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "query 不能为空")
                @Size(max = 500, message = "query 长度不能超过 500")
                String query,
        @Schema(description = "限定文档 ID, 可选; 不传=跨全库") Long docId,
        @Schema(description = "召回 top_k, 默认 5, 范围 [1, 20]")
                @Min(value = 1, message = "top_k 最小 1")
                @Max(value = 20, message = "top_k 最大 20")
                Integer topK) {}
