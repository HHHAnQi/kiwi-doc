package com.xxx.ragdoc.application.document;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@RequiredArgsConstructor
public class DocumentManageService {

    private final DocumentRepository documentRepository;
    private final ParsingTrigger parsingTrigger;

    /** 软删。聚合根内部状态机守门(PARSING 中抛 IllegalStateException → 包装为 409)。 */
    @Transactional
    public void softDelete(Long id) {
        Document doc = loadOrThrow(id);
        try {
            doc.softDelete();
        } catch (IllegalStateException e) {
            log.info("delete.rejected doc_id={}, reason={}", id, e.getMessage());
            throw new DomainException(
                    ErrorCode.DOC_NOT_FAILED, "仅 READY/FAILED 文档可删除, 当前状态=" + doc.status());
        }
        documentRepository.save(doc);
        log.info("delete.done doc_id={}", id);
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
