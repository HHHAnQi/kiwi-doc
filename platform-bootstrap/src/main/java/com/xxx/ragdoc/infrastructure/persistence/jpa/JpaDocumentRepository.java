package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentRepository} 端口的 JPA 适配实现。
 *
 * <p>负责 domain.Document ↔ DocumentEntity 的双向翻译, 让 application 层永远看到领域对象而非 JPA Entity。
 *
 * <p>幂等冲突(MySQL 唯一索引)在此被吞掉并转 null,由 application 层根据 cache 决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaDocumentRepository implements DocumentRepository {

    private final DocumentJpaRepository jpa;
    private final ChunkRepository chunkRepository;

    @Override
    public Document save(Document document) {
        DocumentEntity entity;
        if (document.id() == null) {
            // 新建
            entity = jpa.save(DocumentMapper.toNewEntity(document));
            document.assignId(new DocumentId(entity.getId()));
        } else {
            // 更新(V1 简化:重新 load 后更新状态字段)
            DocumentEntity existed =
                    jpa.findById(document.id().value())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "保存失败: id="
                                                            + document.id()
                                                            + " 的 Document 不存在"));
            jpa.save(DocumentMapper.toEntity(document, existed));
            entity = existed;
        }
        log.debug("Document persisted: id={}, status={}", entity.getId(), entity.getStatus());
        return DocumentMapper.toDomain(entity);
    }

    @Override
    public Optional<Document> findByContentHash(ContentHash hash) {
        try {
            return jpa.findByContentHashAndTenantId(hash.value(), "default")
                    .map(DocumentMapper::toDomain);
        } catch (DataIntegrityViolationException e) {
            // 极端:并发同 hash 写入,按"已存在但未读到"返回空,由 app 层重试读
            log.warn("queryByContentHash 并发冲突: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Document> findById(Long id) {
        return jpa.findById(id).map(DocumentMapper::toDomain);
    }

    @Override
    public long countByStatus(DocumentStatus status) {
        return jpa.countByStatusAndDeletedAtIsNull(status.name());
    }

    @Override
    public Page<DocumentSummary> list(DocumentStatus status, String keyword, Pageable pageable) {
        String statusStr = status == null ? null : status.name();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<DocumentEntity> page = jpa.listForSummary(statusStr, kw, pageable);
        return page.map(
                e ->
                        new DocumentSummary(
                                e.getId(),
                                e.getOriginalFilename(),
                                DocumentStatus.valueOf(e.getStatus()),
                                e.getSizeBytes(),
                                chunkRepository.countByDocumentId(e.getId()),
                                e.getCreatedAt(),
                                e.getUpdatedAt()));
    }

    @Override
    public Optional<DocumentDetail> findDetailById(Long id) {
        return jpa.findById(id)
                .map(
                        e ->
                                new DocumentDetail(
                                        e.getId(),
                                        e.getOriginalFilename(),
                                        e.getMimeType(),
                                        DocumentStatus.valueOf(e.getStatus()),
                                        e.getSizeBytes(),
                                        chunkRepository.countByDocumentId(e.getId()),
                                        e.getRetryCount(),
                                        e.getErrorMessage(),
                                        e.getCreatedAt(),
                                        e.getUpdatedAt()));
    }
}
