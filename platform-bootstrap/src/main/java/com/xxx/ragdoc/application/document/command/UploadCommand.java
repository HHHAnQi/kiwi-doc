package com.xxx.ragdoc.application.document.command;

/**
 * 上传命令(应用层用例入参)。不可变 record。 Controller 把 multipart 转成此 command,service 不感知 HTTP。
 *
 * <p>业务元数据 (source/version/language/docType) 在 V3 迁移后加入; 未传时落库为缺省值 (unknown/null/zh/doc)。 老代码用
 * {@link #ofLegacy} 保持 5 参数零改动。
 */
public record UploadCommand(
        String originalFilename,
        String mimeType,
        long sizeBytes,
        byte[] content,
        String tenantId,
        String source,
        String version,
        String language,
        String docType) {

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
        source = (source == null || source.isBlank()) ? "unknown" : source.trim();
        // version 允许 null(未识别版本)
        language = (language == null || language.isBlank()) ? "zh" : language.trim();
        docType = (docType == null || docType.isBlank()) ? "doc" : docType.trim();
    }

    /** 老调用方兼容入口: 不带业务元数据, 落库为缺省值。 */
    public static UploadCommand ofLegacy(
            String originalFilename,
            String mimeType,
            long sizeBytes,
            byte[] content,
            String tenantId) {
        return new UploadCommand(
                originalFilename,
                mimeType,
                sizeBytes,
                content,
                tenantId,
                "unknown",
                null,
                "zh",
                "doc");
    }
}
