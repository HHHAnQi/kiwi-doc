package com.xxx.ragdoc.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** feedback 列表响应(分页)。 */
@Schema(name = "FeedbackListResponse")
public record FeedbackListResponse(List<FeedbackItem> items, long total, int page, int size) {
    public static FeedbackListResponse of(
            List<FeedbackItem> items, long total, int page, int size) {
        return new FeedbackListResponse(items, total, page, size);
    }
}
