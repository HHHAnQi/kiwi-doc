package com.xxx.ragdoc.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一错误响应,所有 4xx/5xx 必须返回此结构。
 *
 * <pre>
 *   {
 *     "code": "DOC_NOT_FOUND",
 *     "message": "文档不存在",
 *     "trace_id": "a1b2c3d4",
 *     "timestamp": "2026-08-28T10:00:00Z"
 *   }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(code, message, traceId, Instant.now());
    }
}
