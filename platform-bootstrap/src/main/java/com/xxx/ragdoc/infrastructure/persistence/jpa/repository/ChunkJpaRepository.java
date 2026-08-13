package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChunkEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChunkJpaRepository extends JpaRepository<ChunkEntity, Long> {

    /** count 用于文档详情(不强制 join document; 软删 doc 的 chunk 暂不计入可由 service 决定)。 */
    @Query("SELECT COUNT(c) FROM ChunkEntity c, DocumentEntity d WHERE c.documentId=:documentId "
            + "AND d.id=c.documentId AND c.generation=d.activeGeneration")
    long countActiveByDocumentId(@Param("documentId") Long documentId);

    @Query("SELECT COUNT(c) FROM ChunkEntity c, DocumentEntity d WHERE c.documentId=:documentId "
            + "AND d.id=c.documentId AND c.generation=d.activeGeneration AND c.chunkType <> 'PARENT'")
    long countIndexableByDocumentId(@Param("documentId") Long documentId);

    /** 单条: 必须保证父 doc 未软删(Scenario 6 "查已软删文档的 chunk → 404")。 */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.id = :id
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
                  AND c.generation = d.activeGeneration
              )
            """)
    Optional<ChunkEntity> findActiveById(@Param("id") Long id);

    /**
     * 批量: 一次 SQL 拉多个 chunk, 同样校验父 doc 未软删。关联校验放 EXISTS-subquery 而非 join, 让 MySQL 优化器 自行决定 semi-join
     * 策略; ids 空集合 → Spring Data JPA 直接返空, 不发查询。
     */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.id IN :ids
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
                  AND c.generation = d.activeGeneration
              )
            """)
    List<ChunkEntity> findActiveByIdIn(@Param("ids") List<Long> ids);

    /**
     * 按 (docId, seq, chunkType) 精确定位: 同样校验父 doc 未软删。
     *
     * <p>V3 parent-child 切片模式下, 同 (docId, seq) 可能同时存在 PARENT 与 CHILD 两条, 旧版只按 (docId, seq) 查 +
     * Optional getSingleResult 会抛 NonUniqueResultException 导致 /chunks/{id}/neighbors 500。现显式按当前
     * chunk 的 type 过滤, 保证唯一。
     */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.documentId = :docId
              AND c.seq = :seq
              AND c.chunkType = :chunkType
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
                  AND c.generation = d.activeGeneration
              )
            """)
    Optional<ChunkEntity> findActiveByDocAndSeq(
            @Param("docId") Long docId,
            @Param("seq") int seq,
            @Param("chunkType") String chunkType);

    /** 拉取某页全部 chunk: 校验父 doc 未软删, 按 seq 升序。 */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.documentId = :docId
              AND c.page = :page
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
                  AND c.generation = d.activeGeneration
              )
            ORDER BY c.seq ASC
            """)
    List<ChunkEntity> findActiveByDocAndPage(@Param("docId") Long docId, @Param("page") int page);

    /** 所属 doc 中最大的 page 数(无 chunks → 返回 0; Pageable 没传, 直接 SQL)。 */
    @Query(
            """
            SELECT COALESCE(MAX(c.page), 0) FROM ChunkEntity c
            WHERE c.documentId = :docId
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
                  AND c.generation = d.activeGeneration
              )
            """)
    int maxPageOfDocument(@Param("docId") Long docId);

    /** V2: 重新解析前清除旧 chunks。 */
    void deleteByDocumentId(Long documentId);

    void deleteByDocumentIdAndGeneration(Long documentId, Integer generation);
}
