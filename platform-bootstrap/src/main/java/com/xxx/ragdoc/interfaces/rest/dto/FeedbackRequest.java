package com.xxx.ragdoc.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** feedback 提交请求 DTO。 契约见 api-contracts.md §E1。 */
@Schema(name = "FeedbackRequest")
public record FeedbackRequest(
        @Schema(
                        description = "chat 响应中的 trace_id, 仅允许字母数字下划线中划线",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "trace_id 不能为空")
                @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "trace_id 格式非法")
                String traceId,
        @Schema(description = "评分: like / dislike", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "rating 不能为空")
                @Pattern(regexp = "^(like|dislike)$", message = "rating 只能是 like 或 dislike")
                String rating,
        @Schema(description = "纠错答案(选填), ≤5000 字")
                @Size(max = 5000, message = "corrected_answer 长度不能超过 5000")
                String correctedAnswer,
        @Schema(description = "备注(选填), ≤1000 字") @Size(max = 1000, message = "comment 长度不能超过 1000")
                String comment) {}
