package com.xxx.ragdoc.application.document.command;

/**
 * 上传命令(应用层用例入参)。不可变 record。
 * Controller 把 multipart 转成此 command,service 不感知 HTTP。
 */
public record UploadCommand(
        String originalFilename,
        String mimeType,
        long sizeBytes,
        byte[] content,
        String tenantId
) {
    public UploadCommand {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负");
        }
        tenantId = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
    }
}
