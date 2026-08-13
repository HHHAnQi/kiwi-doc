package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, Long> {

    /**
     * 幂等查找(V1 单租户, 含软删 doc)。
     *
     * <p>必须显式查所有(含 deletedAt != NULL): 上层 DocumentUploadService 需要区分"未删=幂等命中" 与"已删=复活 reactivate
     * 走重切路径"。后者是 P3-A 全量重灌的关键(reshash→ 找到软删→ reactivate → parsingTrigger 重新 chunk 到 chunks 表, 不撞
     * documents.uk_content_hash 唯一约束)。
     */
    @Query(
            "SELECT d FROM DocumentEntity d "
                    + "WHERE d.contentHash = :contentHash "
                    + "AND d.tenantId = :tenantId")
    Optional<DocumentEntity> findByContentHashAndTenantId(
            @Param("contentHash") String contentHash, @Param("tenantId") String tenantId);

    /** chat V1 stub 判 EMPTY_KB 用此方法。 */
    long countByStatusAndDeletedAtIsNull(String status);

    /** 分页: 状态可选 + 文件名 keyword 模糊搜索(LIKE %kw%, 大小写不敏感)。 keyword 为空时退化为按 status 过滤。 */
    @Query(
            """
            SELECT d FROM DocumentEntity d
            WHERE d.deletedAt IS NULL
              AND (:status IS NULL OR d.status = :status)
              AND (:keyword IS NULL OR LOWER(d.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY d.createdAt DESC
            """)
    Page<DocumentEntity> listForSummary(
            @Param("status") String status, @Param("keyword") String keyword, Pageable pageable);

    /** Task 11 / P0: tenant + 可选 status/keyword 过滤的 admin 路径 (本 tenant 全可见, 不加 allowedDocIds)。 */
    @org.springframework.data.jpa.repository.Query(
            """
            SELECT d FROM DocumentEntity d
            WHERE d.deletedAt IS NULL
              AND d.tenantId = :tenantId
              AND (:status IS NULL OR d.status = :status)
              AND (:keyword IS NULL OR LOWER(d.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY d.createdAt DESC
            """)
    Page<DocumentEntity> listAccessibleAdmin(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            Pageable pageable);

    /** Task 11 / P0: tenant + allowedDocumentIds 双过滤的普通用户路径。 */
    @org.springframework.data.jpa.repository.Query(
            """
            SELECT d FROM DocumentEntity d
            WHERE d.deletedAt IS NULL
              AND d.tenantId = :tenantId
              AND d.id IN :allowedDocumentIds
              AND (:status IS NULL OR d.status = :status)
              AND (:keyword IS NULL OR LOWER(d.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY d.createdAt DESC
            """)
    Page<DocumentEntity> listAccessibleExplicit(
            @Param("tenantId") String tenantId,
            @Param("allowedDocumentIds") java.util.Set<Long> allowedDocumentIds,
            @Param("status") String status,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * Phase 3 / P3-1: 按 source 找 is_default=true 且 READY 未软删的最新一条。
     *
     * <p>理论返回 0 或 1 条 (同 source 至多 1 个 default, DocumentUploadService + set-default 保证); 加 OrderBy
     * + findFirst 防御数据异常 (DBA 误操作产生 2 条 default 时取最新, 不抛错)。
     */
    Optional<DocumentEntity>
            findFirstBySourceAndStatusAndIsDefaultTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
                    String source, String status);

    /**
     * Phase 3 / P3-1: source 下是否已存在任意未软删的 default 文档 (不限 status)。
     *
     * <p>DocumentUploadService 调用: 新增 doc 时若本查询返 false, 则把新 doc 标 default; 返 true 则不抢 (维持老
     * default)。
     */
    boolean existsBySourceAndIsDefaultTrueAndDeletedAtIsNull(String source);

    boolean existsByTenantIdAndLogicalDocumentKeyAndIsDefaultTrueAndDeletedAtIsNull(
            String tenantId, String logicalDocumentKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT d FROM DocumentEntity d WHERE d.tenantId = :tenantId "
                    + "AND d.logicalDocumentKey = :logicalDocumentKey "
                    + "AND d.isDefault = true AND d.deletedAt IS NULL")
    Optional<DocumentEntity> findCurrentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("logicalDocumentKey") String logicalDocumentKey);

    @Query(
            "SELECT d.id FROM DocumentEntity d WHERE d.tenantId = :tenantId "
                    + "AND d.status = 'INDEXED' AND d.isDefault = true AND d.deletedAt IS NULL "
                    + "AND (:source IS NULL OR d.source = :source)")
    java.util.Set<Long> findCurrentIndexedIds(
            @Param("tenantId") String tenantId, @Param("source") String source);

    @Query(
            "SELECT d FROM DocumentEntity d WHERE d.tenantId = :tenantId "
                    + "AND d.status = 'INDEXED' AND d.deletedAt IS NULL "
                    + "AND (:source IS NULL OR d.source = :source) "
                    + "AND (:version IS NULL OR d.version = :version) "
                    + "AND (:language IS NULL OR d.language = :language)")
    java.util.List<DocumentEntity> findRetrievableForGenerationFilter(
            @Param("tenantId") String tenantId,
            @Param("source") String source,
            @Param("version") String version,
            @Param("language") String language);

    /**
     * Phase 3 / P3-2: MilvusDeleteSweeper 定时拉取 pending_milvus_delete=true 的文档重试删除。 用 Pageable
     * 控制单批上限 (Spring Data 不支持 TopN + 自定义 OrderBy 直接派生, 用 Pageable 更显式)。
     */
    java.util.List<DocumentEntity> findByPendingMilvusDeleteTrueOrderByIdAsc(
            org.springframework.data.domain.Pageable pageable);

    /**
     * V9 RAG-Perm-001: 拿某 tenant 下所有 "非 PRIVATE" (TENANT/PUBLIC) 文档 id, 用于 PermissionResolver
     * 同租户可见集合的兜底。PRIVATE 文档必须通过 ACL/owner 显式授权, 不在此集合。
     */
    @Query(
            "SELECT d.id FROM DocumentEntity d "
                    + "WHERE d.tenantId = :tenantId "
                    + "AND d.deletedAt IS NULL "
                    + "AND d.visibility <> 'PRIVATE'")
    java.util.List<Long> findNonPrivateDocIdsByTenant(@Param("tenantId") String tenantId);

    /** V9 RAG-Perm-001: 拿所有 PUBLIC 文档 id (跨租户公开), 用于 PermissionResolver 同租户并集的扩展集。 */
    @Query(
            "SELECT d.id FROM DocumentEntity d "
                    + "WHERE d.deletedAt IS NULL "
                    + "AND d.visibility = 'PUBLIC'")
    java.util.List<Long> findPublicDocIds();

    /**
     * Task 4: 拿 INDEXED 且未软删的文档 (reconcile 查 Milvus 向量丢失用)。
     *
     * <p>按 lastStateChangeAt 升序, 优先查老文档 (老数据更可能因历史事故丢向量)。
     */
    java.util.List<DocumentEntity> findByStatusAndDeletedAtIsNullOrderByLastStateChangeAtAsc(
            String status, org.springframework.data.domain.Pageable pageable);

    /**
     * Task 4: 查 in-flight 中间态且 last_state_change_at 早于阈值的文档 (reconcile 扫卡死)。
     *
     * <p>阈值由调用方算好 (now - thresholdMinutes) 传入; 命中状态枚举严格写死防 SQL 注入 (枚举字符串)。
     */
    @Query(
            "SELECT d FROM DocumentEntity d "
                    + "WHERE d.deletedAt IS NULL "
                    + "AND d.status IN ('PARSING','CHUNKED','EMBEDDING','INDEXING') "
                    + "AND d.lastStateChangeAt < :threshold "
                    + "ORDER BY d.lastStateChangeAt ASC")
    java.util.List<DocumentEntity> findStuckInPipeline(
            @Param("threshold") java.time.Instant threshold,
            org.springframework.data.domain.Pageable pageable);

    @Query(
            "SELECT d FROM DocumentEntity d WHERE d.status='UPLOADED' "
                    + "AND d.deletedAt IS NULL AND d.createdAt<:olderThan "
                    + "AND NOT EXISTS (SELECT t.id FROM ParseTaskEntity t WHERE t.documentId=d.id) "
                    + "ORDER BY d.id ASC")
    java.util.List<DocumentEntity> findUploadedWithoutParseTask(
            @Param("olderThan") java.time.Instant olderThan,
            org.springframework.data.domain.Pageable pageable);
}
