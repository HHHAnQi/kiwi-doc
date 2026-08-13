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
        String docType,
        String logicalDocumentKey) {

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
        logicalDocumentKey = normalizeLogicalDocumentKey(logicalDocumentKey, originalFilename);
    }

    /** V16 之前的构造器：未显式提供逻辑文档键时，从文件名稳定推导。 */
    public UploadCommand(
            String originalFilename,
            String mimeType,
            long sizeBytes,
            byte[] content,
            String tenantId,
            String source,
            String version,
            String language,
            String docType) {
        this(
                originalFilename,
                mimeType,
                sizeBytes,
                content,
                tenantId,
                source,
                version,
                language,
                docType,
                null);
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
                "doc",
                null);
    }

    private static String normalizeLogicalDocumentKey(String explicit, String filename) {
        if (explicit != null && !explicit.isBlank()) {
            String normalized = explicit.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.length() > 128) {
                throw new IllegalArgumentException("logicalDocumentKey 长度不能超过 128");
            }
            return normalized;
        }
        String base = filename.toLowerCase(java.util.Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        // 只剥离独立的版本段，避免把普通文件名中的数字误当成版本。
        base = base.replaceAll(
                        "(?i)(?:^|[-_\\s])v?\\d+\\.\\d+(?:\\.\\d+){0,2}(?:[-_.]?(?:rc|ga|m|alpha|beta)\\d?)?(?=$|[-_\\s])",
                        "-")
                .replaceAll("[^a-z0-9\\p{IsHan}._-]+", "-")
                .replaceAll("[-_]{2,}", "-")
                .replaceAll("^[-_.]+|[-_.]+$", "");
        if (base.isBlank()) {
            throw new IllegalArgumentException("无法从文件名推导 logicalDocumentKey，请显式传入");
        }
        return base.length() > 128 ? base.substring(0, 128) : base;
    }
}
