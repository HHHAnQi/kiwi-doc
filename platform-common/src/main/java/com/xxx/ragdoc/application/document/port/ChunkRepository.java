package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
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

    /** 实际应进入向量索引的 Chunk 数；Parent 只用于回链，不计入。 */
    default long countIndexableByDocumentId(Long documentId) {
        return countByDocumentId(documentId);
    }

    /** 单条 chunk(关联校验父 doc 未软删)。 */
    Optional<Chunk> findById(Long chunkId);

    /**
     * 批量查 chunks(一次 SQL, 关联校验父 doc 未软删)。
     *
     * <p>Phase 0.3 改造引入: 消除 RetrieveService 的 N+1 查询(原先循环 findById 拉召回结果 + 又 N+1 的 parent 反链)。返回
     * list 顺序不保证与入参 ids 一致 —— 调用方(RetrieveService)自己按 hit 序保序, 这里只求"一次 SQL"。
     *
     * <p>空 ids 入参 → 直接返回空 list(不去数据库)。
     */
    List<Chunk> findByIdIn(List<Long> ids);

    /**
     * 按 docId + seq + chunkType 精确定位相邻 chunk(用于 prev/next 查询)。
     *
     * <p>V3 parent-child 模式下同 (docId, seq) 可能存在 PARENT 与 CHILD 两条, 必须按 type 消歧; 不传 type 会触发
     * NonUniqueResultException → /chunks/{id}/neighbors 500。 查不到返回 empty。
     */
    Optional<Chunk> findByDocumentIdAndSeq(Long documentId, int seq, ChunkType chunkType);

    /** 批量拉多个 anchor 的同文档、同 generation、同类型相邻 chunk，避免 rerank 邻居扩展 N+1。 */
    default List<Chunk> findActiveNeighbors(List<Long> anchorIds, int window) {
        return List.of();
    }

    /** 按 docId + page 拉取该页全部 chunk(seq 升序)。 */
    List<Chunk> findByDocumentIdAndPageOrderBySeq(Long documentId, int page);

    /** 所属 doc 的最大 page 数(前端分页跳转用)。无 chunks 时返回 0。 */
    int maxPageOfDocument(Long documentId);

    // ===== V2 写入能力 =====

    /**
     * 批量保存 chunks(V2: TikaParsingTrigger 解析后调用)。 实现需保证原子性: 同一 documentId 的旧 chunks 应先清除(重新解析场景)。
     *
     * @return 已保存的 chunks(含生成的 id)
     */
    List<Chunk> saveAll(Long documentId, List<Chunk> chunks);

    /** 替换指定影子 generation，不影响 active generation。 */
    default List<Chunk> saveAll(Long documentId, int generation, List<Chunk> chunks) {
        return saveAll(documentId, chunks.stream().map(c -> c.withGeneration(generation)).toList());
    }

    /**
     * 追加保存 chunks(不清旧, 不删已有)。 供 Parent-Child 模式多阶段写入: 先 saveAll(parents), 再用拿到的 parent id 构造 child
     * 调本方法。
     *
     * @return 已保存的 chunks(含生成的 id)
     */
    List<Chunk> saveAllAppend(Long documentId, List<Chunk> chunks);

    default List<Chunk> saveAllAppend(Long documentId, int generation, List<Chunk> chunks) {
        return saveAllAppend(
                documentId, chunks.stream().map(c -> c.withGeneration(generation)).toList());
    }

    default void deleteByDocumentIdAndGeneration(Long documentId, int generation) {
        deleteByDocumentId(documentId);
    }

    /** 删除指定文档的所有 chunks(重新解析前调用, 含 Milvus 向量清理由 service 协调)。 */
    void deleteByDocumentId(Long documentId);
}
