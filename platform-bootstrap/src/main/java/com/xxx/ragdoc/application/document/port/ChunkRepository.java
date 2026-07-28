package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.domain.document.Chunk;
import java.util.List;
import java.util.Optional;

/**
 * Chunk 仓储端口。
 *
 * <p>设计原则: 同一实体(domain.Chunk)的查询端口归一在此, 而非按功能点拆 2 个端口。 写入(write)由 V2 parsing-service 接入后调用 save; V1
 * 不会写入, 仅 read + count。
 *
 * <p>查询都需保证父 doc 未软删: 不返回已软删文档的 chunk(由 JPA 查询 join 兜底, ADR-0024 第 12 轮)。
 */
public interface ChunkRepository {

    /** 按文档 id 统计 chunks 数量(文档详情用)。 */
    long countByDocumentId(Long documentId);

    /** 单条 chunk(关联校验父 doc 未软删)。 */
    Optional<Chunk> findById(Long chunkId);

    /** 按 docId + seq 精确定位相邻 chunk(用于 prev/next 查询)。 查不到返回 empty。 */
    Optional<Chunk> findByDocumentIdAndSeq(Long documentId, int seq);

    /** 按 docId + page 拉取该页全部 chunk(seq 升序)。 */
    List<Chunk> findByDocumentIdAndPageOrderBySeq(Long documentId, int page);

    /** 所属 doc 的最大 page 数(前端分页跳转用)。无 chunks 时返回 0。 */
    int maxPageOfDocument(Long documentId);
}
