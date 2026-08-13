package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.auth.AclWriterPort;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.document.command.UploadCommand;
import com.xxx.ragdoc.application.document.command.UploadResult;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.application.document.port.MetadataExtractor;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.ContentHash;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 上传用例(应用服务)。
 *
 * <p>职责:
 *
 * <ul>
 *   <li>计算 content_hash 做上传幂等
 *   <li>校验文件类型/大小(白名单)
 *   <li>持久化 Document 元数据(status=UPLOADED)
 *   <li>落原始文件到 MinIO
 *   <li>触发解析(V1 同步 stub;V2 真接入 parser)
 * </ul>
 *
 * <p>一致性保证见 docs/architecture/consistency.md。
 */
@Slf4j
@Service
public class DocumentUploadService {

    private static final Set<String> ALLOWED_MIME =
            Set.of("application/pdf", "text/markdown", "text/html", "text/plain");

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ParsingTrigger parsingTrigger;

    /** P2-1: 上传元数据 rule-based 抽取 (源组件 / 版本号 / 文档类型 / 语言)。只填空白, 不覆盖用户显式输入。 */
    private final MetadataExtractor metadataExtractor;

    /** V9 RAG-Perm-001: 把 owner ACL + visibility 落库。 注入 port 而非 infra Repository — 维持 DDD 分层。 */
    private final AclWriterPort aclWriter;

    /**
     * 编程式短事务: 只持有写 doc 行的部分(MS 级), 不包 MinIO/parse/embed。
     *
     * <p>P3-A 全量重灌首批 187/200 fail 根因: {@code @Transactional} 类级标注让整个 upload() 含
     * parsingTrigger(embed 5-10min) 全在一个事务里, doc 行锁持有过久 → 下个请求 Lock wait timeout。 拆事务边界: doc 写入短事务
     * commit 释放锁; parse 跑在事务外(失败用 markFailed 单独短事务回写)。
     */
    private final TransactionTemplate shortTxWrite;

    public DocumentUploadService(
            DocumentRepository documentRepository,
            FileStorage fileStorage,
            ParsingTrigger parsingTrigger,
            MetadataExtractor metadataExtractor,
            AclWriterPort aclWriter,
            PlatformTransactionManager txManager) {
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.parsingTrigger = parsingTrigger;
        this.metadataExtractor = metadataExtractor;
        this.aclWriter = aclWriter;
        this.shortTxWrite = new TransactionTemplate(txManager);
        // 短事务: 仅包 doc 写, 不传播外层(虽然 upload() 本身不带 @Transactional, 防御未来变更)
        this.shortTxWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Disable class-level @Transactional(P3-A 重灌死锁根因)。事务边界下沉到 doc 写入段, parse 在事务外。 */
    public UploadResult upload(UploadCommand cmd) {
        // P2-1: 自动抽取 metadata 减少手动输入。只填空白字段, 用户显式传的始终胜出。
        // 在 validate / 业务合法性校验前执行, 让下游所有逻辑用 enriched cmd。
        cmd = metadataExtractor.enrich(cmd);
        validate(cmd);

        ContentHash hash = computeSha256(cmd.content());
        log.info(
                "upload.start filename={}, size={}, hash={}...",
                cmd.originalFilename(),
                cmd.sizeBytes(),
                hash.value().substring(0, 8));

        // ============ 幂等 / 复活 ============
        // 1. 未删 doc 同 hash → idempotent_hit 直接返回(不重切)
        // 2. 软删 doc 同 hash → reactivate 复活(保留原 doc_id) → 触发重切 chunks → parent-child 重灌路径
        // 3. 全新 hash → 走正常 insert
        var existed = documentRepository.findByContentHash(hash, cmd.tenantId());
        if (existed.isPresent()) {
            Document d = existed.get();
            if (!d.deleted()) {
                log.info(
                        "upload.idempotent_hit doc_id={}, hash={}",
                        d.id().value(),
                        d.contentHash().value());
                return new UploadResult(d.id().value(), d.status(), d.originalFilename(), true);
            }
            // 软删命中: reactivate 包在短事务里, commit 释放锁
            d.reactivate();
            // P3-1: reactivate 后, 若该 doc 之前的 isDefault=true 因为是 same-session reload 仍保留;
            // 但若之前 false 且 source 内其他 default 也被软删/不存在, 需要重新标 default,
            // 否则 RetrieveService 在该 source 找不到 default 走全库混查 (P0 bug 复发)。
            // existsDefaultBySource 查的是 deletedAt IS NULL 的 default, 此时 reactivate 已
            // setDeleted=false,
            // 若本 doc 之前 isDefault=true 它已被计入, 调值=true 不抢;
            // 若本 doc isDefault=false 且无其他 default → 重标它。
            if (!d.isDefault()
                    && !documentRepository.existsCurrentByLogicalKey(
                            d.tenantId(), d.logicalDocumentKey())) {
                d.markDefault();
                log.info(
                        "upload.reactivate_mark_default doc_id={}, source={}, reason=no_existing_default",
                        d.id().value(),
                        d.source());
            }
            shortTxWrite.executeWithoutResult(status -> documentRepository.save(d));
            log.info(
                    "upload.reactivate doc_id={}, hash={}, before=DELETED",
                    d.id().value(),
                    d.contentHash().value());

            // MinIO 覆盖上传(无锁)
            try {
                fileStorage.uploadRaw(d.id().value(), cmd.originalFilename(), cmd.content());
                log.debug("upload.file_stored(overwrite) objectKey={}", d.id().value());
            } catch (Exception e) {
                log.error("upload.minio_failed(reactivate) doc_id={}", d.id(), e);
                d.markFailed("文件存储失败: " + e.getMessage());
                final Document failMark = d;
                shortTxWrite.executeWithoutResult(s -> documentRepository.save(failMark));
                throw new DomainException(ErrorCode.SYS_INTERNAL, "文件存储失败");
            }
            // parse 在事务外: chunks 写入走自己的事务(JpaChunkRepository.saveAll 有 @Transactional)
            parsingTrigger.trigger(d.id().value());
            // 重读最新状态(parse 已把 status=READY 写回)
            Document fresh = documentRepository.findById(d.id().value()).orElseThrow();
            return new UploadResult(
                    fresh.id().value(), fresh.status(), fresh.originalFilename(), false);
        }

        // ============ 创建聚合根并持久化(短事务) ============
        Document draft =
                Document.newUploaded(
                        hash,
                        cmd.originalFilename(),
                        cmd.mimeType(),
                        cmd.sizeBytes(),
                        cmd.tenantId(),
                        cmd.source(),
                        cmd.version(),
                        cmd.language(),
                        cmd.docType(),
                        cmd.logicalDocumentKey());
        // 同一逻辑文档首次上传 / 老 current 全软删 → 自动标新版本为 current。
        // 不限 status (因为新 doc 当前是 UPLOADED, parsingTrigger 还没跑完; READY 二次过滤由
        // RetrieveService.findDefaultReadyBySource 兜底)。
        boolean shouldMarkDefault =
                !documentRepository.existsCurrentByLogicalKey(
                        draft.tenantId(), draft.logicalDocumentKey());
        if (shouldMarkDefault) {
            draft.markDefault();
            log.info("upload.mark_default source={}, reason=no_existing_default", draft.source());
        }
        Document document = shortTxWrite.execute(status -> documentRepository.save(draft));

        // V9 RAG-Perm-001: 新建文档落 owner ACL + visibility
        //   - 默认主体 (无 token / dev) → owner=dev, visibility=TENANT (单租户兼容: 同租户可见)
        //   - 用户带 token 登录 → owner=该 userId, visibility=TENANT (后续可由 admin 改 PRIVATE/PUBLIC)
        //   - ACL 是检索授权的 truthful source；写入失败必须失败关闭，禁止产生无主文档。
        try {
            aclWriter.grantOwnerAcl(
                    document.id().value(), AuthContext.currentPrincipal().userId(), "TENANT");
        } catch (Exception e) {
            log.error(
                    "upload.acl_grant_failed doc_id={}, error={} (fail closed)",
                    document.id().value(),
                    e.getMessage());
            document.markFailed("ACL 初始化失败: " + e.getMessage());
            final Document failMark = document;
            shortTxWrite.executeWithoutResult(s -> documentRepository.save(failMark));
            throw new DomainException(ErrorCode.SYS_INTERNAL, "文档权限初始化失败");
        }

        // ============ 落 MinIO(无锁) ============
        try {
            String objectKey =
                    fileStorage.uploadRaw(
                            document.id().value(), cmd.originalFilename(), cmd.content());
            log.debug("upload.file_stored objectKey={}", objectKey);
        } catch (Exception e) {
            log.error("upload.minio_failed doc_id={}", document.id(), e);
            document.markFailed("文件存储失败: " + e.getMessage());
            final Document failMark = document;
            shortTxWrite.executeWithoutResult(s -> documentRepository.save(failMark));
            throw new DomainException(ErrorCode.SYS_INTERNAL, "文件存储失败");
        }

        // ============ 触发解析(事务外; parse 内部有自己的 @Transactional) ============
        parsingTrigger.trigger(document.id().value());

        // 重读最新 status(parse 把 status=READY 写回但 document 实体未持有, 因为是 PARALLEL 写入)
        Document fresh = documentRepository.findById(document.id().value()).orElseThrow();
        return new UploadResult(
                fresh.id().value(), fresh.status(), fresh.originalFilename(), false);
    }

    // ============================================================
    // 私有辅助
    // ============================================================

    private static void validate(UploadCommand cmd) {
        if (!ALLOWED_MIME.contains(cmd.mimeType())) {
            throw new DomainException(
                    ErrorCode.DOC_INVALID_TYPE, "mime_type=" + cmd.mimeType() + " 不在白名单");
        }
        if (cmd.sizeBytes() > 50L * 1024 * 1024) {
            throw new DomainException(ErrorCode.DOC_TOO_LARGE, "文件超 50MB");
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
