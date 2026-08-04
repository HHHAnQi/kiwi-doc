package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 文档管理用例(写路径:软删 + 重试)。
 *
 * <p>状态机规则在 domain 层强制:
 *
 * <ul>
 *   <li>软删: 仅 READY/FAILED 可删, PARSING 中拒绝
 *   <li>重试: 仅 FAILED 且 retry_count=0 可重试一次
 * </ul>
 *
 * <p>详见 docs/architecture/state-machines.md。
 */
@Slf4j
@Service
public class DocumentManageService {

    private final DocumentRepository documentRepository;
    private final ParsingTrigger parsingTrigger;
    // Phase 3 / P3-2: 软删同步清 chunks (in-tx 原子) + Milvus 向量 (out-of-tx, 失败标 pending 由 sweeper 重试)
    private final ChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    /**
     * Phase 3 / P3-2: Milvus 重试路径用编程式短事务, 避免 @Transactional 把 Milvus 远程调用 (秒级)
     * 包进事务持锁过久 (复用 DocumentUploadService 教训: P3-A 重灌死锁根因)。
     */
    private final TransactionTemplate shortTx;

    public DocumentManageService(
            DocumentRepository documentRepository,
            ParsingTrigger parsingTrigger,
            ChunkRepository chunkRepository,
            VectorStore vectorStore,
            TransactionTemplate shortTx) {
        this.documentRepository = documentRepository;
        this.parsingTrigger = parsingTrigger;
        this.chunkRepository = chunkRepository;
        this.vectorStore = vectorStore;
        // 调用方(配置层)负责创建 TransactionTemplate 并设 propagation = REQUIRES_NEW,
        // 让 Milvus 异步重试路径不污染外层 tx (即使有外层 @Transactional 的 retry() 路径)。可单测直接传入。
        this.shortTx = shortTx;
    }

    /**
     * 软删。聚合根内部状态机守门(PARSING 中抛 IllegalStateException → 包装为 409)。
     *
     * <p>Phase 3 / P3-2 P0 fix: 软删前 chunks + Milvus 向量均跟随清理, 杜绝"幽灵召回"。
     *
     * <p>一致性策略 (类似 DocumentUploadService P3-A 教训: 不要把 Milvus / 远程调用包进 @Transactional):
     *
     * <ol>
     *   <li>短事务 (REQUIRES_NEW): 设软删标志 + unmark default + mark pending=true + MySQL 删 chunks + save doc。
     *   <li>事务外调 Milvus delete (走 attemptMilvusDelete: 成功 → 短事务清 pending; 失败 → 保留 pending 等 sweeper)。
     * </ol>
     *
     * <p>因此本方法无 @Transactional 注解, 改用 shortTx 编程式边界。
     */
    public void softDelete(Long id) {
        Document doc = loadOrThrow(id);
        try {
            doc.softDelete();
        } catch (IllegalStateException e) {
            log.info("delete.rejected doc_id={}, reason={}", id, e.getMessage());
            throw new DomainException(
                    ErrorCode.DOC_NOT_FAILED, "仅 READY/FAILED 文档可删除, 当前状态=" + doc.status());
        }
        // 同 source default 文档被软删 → unmark default, 让 DocumentUploadService / set-default 下次决策正确。
        if (doc.isDefault()) {
            doc.unmarkDefault();
            log.info("delete.unmark_default doc_id={}, source={}", id, doc.source());
        }
        // 设 pending=true: Milvus 同步失败时 sweeper 重试; 同步成功时 attemptMilvusDelete 短事务清标。
        doc.markPendingMilvusDelete();
        // 短事务: chunks 删除 + documents 软删标志 + pending 标记 → 原子提交。Milvus 远程调用不能进这个 tx。
        shortTx.executeWithoutResult(
                status -> {
                    chunkRepository.deleteByDocumentId(id);
                    documentRepository.save(doc);
                });
        log.info(
                "delete.soft_deleted doc_id={}, source={}, version={}, pending_milvus_delete=true",
                id,
                doc.source(),
                doc.version());

        // 事务外: 同步尝试删 Milvus 减少窗口。失败保留 pending, sweeper 兜底。
        attemptMilvusDelete(doc);
    }

    /**
     * Phase 3 / P3-2: 同步尝试删 Milvus 向量; 成功清 pending, 失败保留 pending。
     *
     * <p>Milvus 远程调用 (秒级) 不能进 @Transactional / 不能进 JPA save tx, 否则 MySQL 行锁持有过久 (P3-A
     * DocumentUploadService 死锁根因相同)。这里用编程式短事务: Milvus 调用 *在 tx 外*, save *在独立短 tx 内*。
     *
     * <p>无 @Transactional 注解: 显式不要让 Spring 用代理为整个方法开 tx; save 由 shortTx REQUIRES_NEW 隔离。
     *
     * @return true=本次删除成功并清标; false=失败保留 pending
     */
    public boolean attemptMilvusDelete(Document doc) {
        try {
            // Milvus 调用在事务外: 远程 / 慢 / 可能熔断, 不持 MySQL 锁
            vectorStore.deleteByDocumentId(doc.id().value());
            // 成功 → 清 pending 标记, 短事务提交
            doc.clearPendingMilvusDelete();
            shortTx.executeWithoutResult(status -> documentRepository.save(doc));
            log.info("delete.milvus_success doc_id={}, source={}", doc.id().value(), doc.source());
            return true;
        } catch (Exception e) {
            // CB open / Milvus 不可达 / 超时。保留 pending=true (上次 softDelete 短事务已标), 等下个 sweeper 周期重试。
            log.warn(
                    "delete.milvus_failed_keep_pending doc_id={}, source={}, error={}",
                    doc.id().value(),
                    doc.source(),
                    e.getMessage());
            return false;
        }
    }

    /**
     * 重试失败文档。 - 非 FAILED → DOC_NOT_FAILED(409) - retry_count 已达 1 → DOC_NOT_FAILED(409, 提示联系管理员)
     */
    @Transactional
    public void retry(Long id) {
        Document doc = loadOrThrow(id);
        if (!doc.status().equals(com.xxx.ragdoc.domain.document.DocumentStatus.FAILED)) {
            throw new DomainException(
                    ErrorCode.DOC_NOT_FAILED, "仅 FAILED 文档可重试, 当前状态=" + doc.status());
        }
        if (!doc.canRetry()) {
            throw new DomainException(ErrorCode.DOC_NOT_FAILED, "已重试 1 次, 联系管理员");
        }
        doc.retry();
        documentRepository.save(doc);
        log.info("retry.triggered doc_id={}, retry_count={}", id, doc.retryCount());

        // 状态已变 PARSING, 触发实际解析(stub V1: 仅状态机动作)
        parsingTrigger.trigger(id);
    }

    private Document loadOrThrow(Long id) {
        return documentRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.DOC_NOT_FOUND, "文档不存在: " + id));
    }
}
