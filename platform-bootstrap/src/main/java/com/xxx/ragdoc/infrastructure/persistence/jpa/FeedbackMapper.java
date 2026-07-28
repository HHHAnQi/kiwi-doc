package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
import com.xxx.ragdoc.domain.shared.TraceId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.FeedbackEntity;

/**
 * domain.Feedback ↔ FeedbackEntity 双向转换器。
 *
 * <p>HTML 转义在 {@link #toNewEntity} 时执行(domain 保留原文, 持久化层转义)。
 */
public final class FeedbackMapper {

    private FeedbackMapper() {}

    public static Feedback toDomain(FeedbackEntity e) {
        return Feedback.restore(
                e.getId(),
                new TraceId(e.getTraceId()),
                Rating.parse(e.getRating()),
                e.getCorrectedAnswer(),
                e.getComment(),
                e.getUserId(),
                e.getCreatedAt());
    }

    public static FeedbackEntity toNewEntity(
            Feedback f, String escapedCorrected, String escapedComment) {
        FeedbackEntity e = new FeedbackEntity();
        e.setTraceId(f.traceId().value());
        e.setRating(f.rating().dbValue());
        e.setCorrectedAnswer(escapedCorrected);
        e.setComment(escapedComment);
        e.setUserId(f.userId());
        return e;
    }
}
