package com.xxx.ragdoc.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** feedback 提交响应。 */
@Schema(name = "FeedbackCreatedResponse")
public record FeedbackCreatedResponse(Long feedbackId) {}
