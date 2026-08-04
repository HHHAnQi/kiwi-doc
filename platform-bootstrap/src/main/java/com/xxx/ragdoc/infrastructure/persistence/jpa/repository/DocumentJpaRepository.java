package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Phase 3 / P3-1: 按 source 找 is_default=true 且 READY 未软删的最新一条。
     *
     * <p>理论返回 0 或 1 条 (同 source 至多 1 个 default, DocumentUploadService + set-default 保证);
     * 加 OrderBy + findFirst 防御数据异常 (DBA 误操作产生 2 条 default 时取最新, 不抛错)。
     */
    Optional<DocumentEntity>
            findFirstBySourceAndStatusAndIsDefaultTrueAndDeletedAtIsNullOrderByCreatedAtDesc(
                    String source, String status);

    /**
     * Phase 3 / P3-1: source 下是否已存在任意未软删的 default 文档 (不限 status)。
     *
     * <p>DocumentUploadService 调用: 新增 doc 时若本查询返 false, 则把新 doc 标 default;
     * 返 true 则不抢 (维持老 default)。
     */
    boolean existsBySourceAndIsDefaultTrueAndDeletedAtIsNull(String source);

    /**
     * Phase 3 / P3-2: MilvusDeleteSweeper 定时拉取 pending_milvus_delete=true 的文档重试删除。
     * 用 Pageable 控制单批上限 (Spring Data 不支持 TopN + 自定义 OrderBy 直接派生, 用 Pageable 更显式)。
     */
    java.util.List<DocumentEntity> findByPendingMilvusDeleteTrueOrderByIdAsc(
            org.springframework.data.domain.Pageable pageable);
}
