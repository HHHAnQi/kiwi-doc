package com.xxx.ragdoc.application.feedback.command;

import com.xxx.ragdoc.domain.feedback.Rating;

/** 提交反馈用例入参。 traceId 格式校验在这里做(防任意字符串到达 service 的 SQL/MDC)。 */
public record FeedbackCommand(
        String traceId, Rating rating, String correctedAnswer, String comment, String userId) {
    private static final java.util.regex.Pattern TRACE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public FeedbackCommand {
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId 格式非法");
        }
        if (rating == null) {
            throw new IllegalArgumentException("rating 不能为空");
        }
        userId = (userId == null || userId.isBlank()) ? "default" : userId;
    }
}
