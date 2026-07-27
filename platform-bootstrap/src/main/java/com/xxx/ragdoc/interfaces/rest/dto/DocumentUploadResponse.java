package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.document.command.UploadResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 上传响应 DTO,对外稳定契约。
 */
@Schema(name = "DocumentUploadResponse")
public record DocumentUploadResponse(
        @Schema(description = "文档 ID") Long docId,
        @Schema(description = "状态", example = "PARSING") String status,
        @Schema(description = "原文件名") String originalFilename,
        @Schema(description = "是否幂等命中已有文档") boolean idempotentHit,
        Instant receivedAt
) {
    public static DocumentUploadResponse from(UploadResult r) {
        return new DocumentUploadResponse(
                r.docId(),
                r.status().name(),
                r.originalFilename(),
                r.idempotentHit(),
                Instant.now());
    }
}
