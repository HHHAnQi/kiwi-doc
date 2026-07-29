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
    long countByDocumentId(Long documentId);

    /** 单条: 必须保证父 doc 未软删(Scenario 6 "查已软删文档的 chunk → 404")。 */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.id = :id
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
              )
            """)
    Optional<ChunkEntity> findActiveById(@Param("id") Long id);

    /** 按 (docId, seq) 精确定位: 同样校验父 doc 未软删。 */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.documentId = :docId
              AND c.seq = :seq
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
              )
            """)
    Optional<ChunkEntity> findActiveByDocAndSeq(@Param("docId") Long docId, @Param("seq") int seq);

    /** 拉取某页全部 chunk: 校验父 doc 未软删, 按 seq 升序。 */
    @Query(
            """
            SELECT c FROM ChunkEntity c
            WHERE c.documentId = :docId
              AND c.page = :page
              AND EXISTS (
                SELECT 1 FROM DocumentEntity d
                WHERE d.id = c.documentId AND d.deletedAt IS NULL
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
              )
            """)
    int maxPageOfDocument(@Param("docId") Long docId);

    /** V2: 重新解析前清除旧 chunks。 */
    void deleteByDocumentId(Long documentId);
}
