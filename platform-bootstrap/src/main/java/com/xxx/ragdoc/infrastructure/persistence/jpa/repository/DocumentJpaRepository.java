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
}
