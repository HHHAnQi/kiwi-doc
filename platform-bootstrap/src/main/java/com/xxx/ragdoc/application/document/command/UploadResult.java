package com.xxx.ragdoc.application.document.command;

import com.xxx.ragdoc.domain.document.DocumentStatus;

/**
 * 上传结果(应用层用例出参)。
 */
public record UploadResult(
        Long docId,
        DocumentStatus status,
        String originalFilename,
        boolean idempotentHit
) {
}
