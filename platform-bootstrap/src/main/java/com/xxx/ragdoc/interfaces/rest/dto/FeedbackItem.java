package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.domain.feedback.Feedback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** feedback 单条记录响应 DTO。 不返回 query / answer (chat_traces V1 不存原 query)。 */
@Schema(name = "FeedbackItem")
public record FeedbackItem(
        Long feedbackId,
        String traceId,
        String rating,
        String correctedAnswer,
        String comment,
        Instant createdAt) {
    public static FeedbackItem from(Feedback f) {
        return new FeedbackItem(
                f.id(),
                f.traceId().value(),
                f.rating().dbValue(),
                f.correctedAnswer(),
                f.comment(),
                f.createdAt());
    }
}
