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
    public Optional<Document> findByContentHash(ContentHash hash, String tenantId) {
        try {
            return jpa.findByContentHashAndTenantId(hash.value(), tenantId)
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
    public java.util.List<Document> findByIdIn(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.List.of();
        return jpa.findAllById(ids).stream().map(DocumentMapper::toDomain).toList();
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
                                e.getUpdatedAt(),
                                e.getSource(),
                                e.getVersion(),
                                e.getLogicalDocumentKey(),
                                e.getLanguage(),
                                e.getDocType(),
                                Boolean.TRUE.equals(e.getIsDefault()), // P3-1
                                Boolean.TRUE.equals(e.getPendingMilvusDelete()))); // P3-2
    }

    @Override
    public Page<DocumentSummary> listAccessible(
            String tenantId,
            java.util.Set<Long> allowedDocumentIds,
            DocumentStatus status,
            String keyword,
            Pageable pageable) {
        String statusStr = status == null ? null : status.name();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<DocumentEntity> page;
        if (allowedDocumentIds == null) {
            // 本 tenant admin 路径: 本 tenant 全 doc 可见 (但不跨 tenant)
            page = jpa.listAccessibleAdmin(tenantId, statusStr, kw, pageable);
        } else if (allowedDocumentIds.isEmpty()) {
            // 无任何可访问 doc → 返空页
            return Page.empty(pageable);
        } else {
            page =
                    jpa.listAccessibleExplicit(
                            tenantId, allowedDocumentIds, statusStr, kw, pageable);
        }
        return page.map(
                e ->
                        new DocumentSummary(
                                e.getId(),
                                e.getOriginalFilename(),
                                DocumentStatus.valueOf(e.getStatus()),
                                e.getSizeBytes(),
                                chunkRepository.countByDocumentId(e.getId()),
                                e.getCreatedAt(),
                                e.getUpdatedAt(),
                                e.getSource(),
                                e.getVersion(),
                                e.getLogicalDocumentKey(),
                                e.getLanguage(),
                                e.getDocType(),
                                Boolean.TRUE.equals(e.getIsDefault()),
                                Boolean.TRUE.equals(e.getPendingMilvusDelete())));
    }

    @Override
    public Optional<DocumentDetail> findDetailById(Long id) {
        // DEV-V3-C: 与 listForSummary 一致地过滤 deletedAt IS NOT NULL,
        // 否则软删后 GET /documents/{id} 仍返回 200, 与列表"无声消失"链路不一致。
        // findById 不带过滤, 用 lambda 显式校验 deletedAt == null。
        return jpa.findById(id)
                .filter(e -> e.getDeletedAt() == null)
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
                                        e.getUpdatedAt(),
                                        e.getSource(),
                                        e.getVersion(),
                                        e.getLogicalDocumentKey(),
                                        e.getLanguage(),
                                        e.getDocType(),
                                        Boolean.TRUE.equals(e.getIsDefault()), // P3-1
                                        Boolean.TRUE.equals(e.getPendingMilvusDelete()))); // P3-2
    }

    @Override
    public Optional<Document> findDefaultReadyBySource(String source) {
        if (source == null || source.isBlank()) return Optional.empty();
        // Phase 3 / P3-1: default version fallback 用于 retrieve 时按 source 找最新默认版本过滤,
        // 避免跨版本混查。约定: 同 source + READY + !deleted 最多 1 条 is_default=true.
        return jpa.findFirstBySourceAndStatusAndIsDefaultTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
                        source, DocumentStatus.INDEXED.name())
                .map(DocumentMapper::toDomain);
    }

    @Override
    public boolean existsDefaultBySource(String source) {
        if (source == null || source.isBlank()) return false;
        // P3-1: DocumentUploadService 调用, 判断是否需要标新 doc 为 default。
        // 不限 status 因为此时新 doc 还在 UPLOADED, parsingTrigger 未跑完。READY 由 findDefaultReadyBySource 兜底。
        return jpa.existsBySourceAndIsDefaultTrueAndDeletedAtIsNull(source);
    }

    @Override
    public boolean existsCurrentByLogicalKey(String tenantId, String logicalDocumentKey) {
        if (tenantId == null || tenantId.isBlank()
                || logicalDocumentKey == null || logicalDocumentKey.isBlank()) return false;
        return jpa.existsByTenantIdAndLogicalDocumentKeyAndIsDefaultTrueAndDeletedAtIsNull(
                tenantId, logicalDocumentKey);
    }

    @Override
    public Optional<Document> findCurrentByLogicalKeyForUpdate(
            String tenantId, String logicalDocumentKey) {
        if (tenantId == null || tenantId.isBlank()
                || logicalDocumentKey == null || logicalDocumentKey.isBlank()) {
            return Optional.empty();
        }
        return jpa.findCurrentForUpdate(tenantId, logicalDocumentKey).map(DocumentMapper::toDomain);
    }

    @Override
    public Optional<java.util.Set<Long>> findCurrentIndexedIds(String tenantId, String source) {
        if (tenantId == null || tenantId.isBlank()) return Optional.of(java.util.Set.of());
        String normalizedSource = source == null || source.isBlank() ? null : source.trim();
        return Optional.of(jpa.findCurrentIndexedIds(tenantId, normalizedSource));
    }

    @Override
    public Optional<java.util.Map<Long, Integer>> findActiveGenerations(
            String tenantId,
            String source,
            String version,
            String language,
            java.util.Collection<Long> candidateDocumentIds) {
        if (tenantId == null || tenantId.isBlank()) return Optional.of(java.util.Map.of());
        String normalizedSource = normalize(source);
        String normalizedVersion = normalize(version);
        String normalizedLanguage = normalize(language);
        java.util.stream.Stream<DocumentEntity> documents =
                candidateDocumentIds == null
                        ? jpa.findRetrievableForGenerationFilter(
                                        tenantId,
                                        normalizedSource,
                                        normalizedVersion,
                                        normalizedLanguage)
                                .stream()
                        : jpa.findAllById(candidateDocumentIds).stream();
        java.util.Map<Long, Integer> result = documents
                .filter(e -> tenantId.equals(e.getTenantId()))
                .filter(e -> e.getDeletedAt() == null)
                .filter(e -> DocumentStatus.INDEXED.name().equals(e.getStatus()))
                .filter(e -> normalizedSource == null || normalizedSource.equals(e.getSource()))
                .filter(e -> normalizedVersion == null || normalizedVersion.equals(e.getVersion()))
                .filter(e -> normalizedLanguage == null || normalizedLanguage.equals(e.getLanguage()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DocumentEntity::getId,
                        e -> e.getActiveGeneration() == null ? 1 : e.getActiveGeneration()));
        return Optional.of(result);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public java.util.List<Document> findDocsPendingMilvusDelete(int limit) {
        // P3-2: sweeper 用; 按 id asc 防同一文档跨周期被反复排在后面饿死。
        return jpa
                .findByPendingMilvusDeleteTrueOrderByIdAsc(
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }

    @Override
    public java.util.List<Document> findIndexed(int limit) {
        // Task 4: reconcile 查向量丢失用; INDEXED 是检索终态, 每条都该有向量在 Milvus
        return jpa
                .findByStatusAndDeletedAtIsNullOrderByLastStateChangeAtAsc(
                        DocumentStatus.INDEXED.name(),
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }

    @Override
    public java.util.List<Document> findStuckInPipeline(int thresholdMinutes, int limit) {
        // Task 4: reconcile 扫卡死; 阈值分钟 → 截止时刻
        java.time.Instant threshold =
                java.time.Instant.now()
                        .minus(thresholdMinutes, java.time.temporal.ChronoUnit.MINUTES);
        return jpa
                .findStuckInPipeline(
                        threshold, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }

    @Override
    public java.util.List<Document> findUploadedWithoutParseTask(
            java.time.Instant olderThan, int limit) {
        return jpa.findUploadedWithoutParseTask(
                        olderThan,
                        org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }
}
