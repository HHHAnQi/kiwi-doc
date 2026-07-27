package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.document.command.UploadCommand;
import com.xxx.ragdoc.application.document.command.UploadResult;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.ContentHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * 上传用例(应用服务)。
 *
 * <p>职责:
 * <ul>
 *   <li>计算 content_hash 做上传幂等</li>
 *   <li>校验文件类型/大小(白名单)</li>
 *   <li>持久化 Document 元数据(status=UPLOADED)</li>
 *   <li>落原始文件到 MinIO</li>
 *   <li>触发解析(V1 同步 stub;V2 真接入 parser)</li>
 * </ul>
 *
 * <p>一致性保证见 docs/architecture/consistency.md。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "text/markdown",
            "text/html",
            "text/plain"
    );

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ParsingTrigger parsingTrigger;

    @Transactional
    public UploadResult upload(UploadCommand cmd) {
        validate(cmd);

        ContentHash hash = computeSha256(cmd.content());
        log.info("upload.start filename={}, size={}, hash={}...",
                cmd.originalFilename(), cmd.sizeBytes(), hash.value().substring(0, 8));

        // ============ 幂等 ============
        var existed = documentRepository.findByContentHash(hash);
        if (existed.isPresent()) {
            Document d = existed.get();
            log.info("upload.idempotent_hit doc_id={}, hash={}", d.id(), d.contentHash().value());
            return new UploadResult(
                    d.id().value(), d.status(), d.originalFilename(), true);
        }

        // ============ 创建聚合根并持久化 ============
        Document document = Document.newUploaded(
                hash, cmd.originalFilename(), cmd.mimeType(),
                cmd.sizeBytes(), cmd.tenantId());
        document = documentRepository.save(document);

        // ============ 落 MinIO ============
        try {
            String objectKey = fileStorage.uploadRaw(
                    document.id().value(), cmd.originalFilename(), cmd.content());
            log.debug("upload.file_stored objectKey={}", objectKey);
        } catch (Exception e) {
            log.error("upload.minio_failed doc_id={}", document.id(), e);
            // 不回滚事务(MinIO 文件可清理由定时任务),但标记 FAILED
            document.markFailed("文件存储失败: " + e.getMessage());
            documentRepository.save(document);
            throw new DomainException(ErrorCode.SYS_INTERNAL, "文件存储失败");
        }

        // ============ 触发解析(V1 同步 stub) ============
        parsingTrigger.trigger(document.id().value());

        return new UploadResult(
                document.id().value(),
                document.status(),
                document.originalFilename(),
                false);
    }

    // ============================================================
    // 私有辅助
    // ============================================================

    private static void validate(UploadCommand cmd) {
        if (!ALLOWED_MIME.contains(cmd.mimeType())) {
            throw new DomainException(ErrorCode.DOC_INVALID_TYPE,
                    "mime_type=" + cmd.mimeType() + " 不在白名单");
        }
        if (cmd.sizeBytes() > 50L * 1024 * 1024) {
            throw new DomainException(ErrorCode.DOC_TOO_LARGE,
                    "文件超 50MB");
        }
    }

    private static ContentHash computeSha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return new ContentHash(sb.toString());
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 SHA-256,理论不会到这
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
