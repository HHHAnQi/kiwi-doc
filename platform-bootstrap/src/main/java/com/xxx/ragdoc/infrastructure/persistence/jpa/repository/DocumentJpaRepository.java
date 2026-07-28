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

    /** 幂等查找(V1 单租户)。 */
    Optional<DocumentEntity> findByContentHashAndTenantId(String contentHash, String tenantId);

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
