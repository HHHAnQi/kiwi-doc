package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChunkEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.ChunkJpaRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ChunkRepository} 端口的 JPA 实现。
 *
 * <p>所有"查找"查询都强制父文档未软删(软删文档的 chunk 视为 404, 见 Scenario 6); {@link #countByDocumentId} 用于文档详情本身有过滤回填,
 * 不在这里强加, 由 service 判定。
 */
@Component
@RequiredArgsConstructor
public class JpaChunkRepository implements ChunkRepository {

    private final ChunkJpaRepository jpa;

    @Override
    public long countByDocumentId(Long documentId) {
        return jpa.countByDocumentId(documentId);
    }

    @Override
    public Optional<Chunk> findById(Long chunkId) {
        return jpa.findActiveById(chunkId).map(ChunkMapper::toDomain);
    }

    @Override
    public List<Chunk> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpa.findActiveByIdIn(ids).stream().map(ChunkMapper::toDomain).toList();
    }

    @Override
    public Optional<Chunk> findByDocumentIdAndSeq(Long documentId, int seq, ChunkType chunkType) {
        return jpa
                .findActiveByDocAndSeq(documentId, seq, chunkType == null ? null : chunkType.name())
                .map(ChunkMapper::toDomain);
    }

    @Override
    public List<Chunk> findByDocumentIdAndPageOrderBySeq(Long documentId, int page) {
        return jpa.findActiveByDocAndPage(documentId, page).stream()
                .map(ChunkMapper::toDomain)
                .toList();
    }

    @Override
    public int maxPageOfDocument(Long documentId) {
        return jpa.maxPageOfDocument(documentId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public List<Chunk> saveAll(Long documentId, List<Chunk> chunks) {
        // 重新解析时先清旧(保证幂等: 同一文档重复解析不会产生重复 chunks)
        // P3-3 fix: bulk delete 是 modifying query, 必须在 tx 里 (unarchive 路径从 controller 进入时
        // 当前线程不持有 tx, Hibernate Session 未挂 → TransactionRequiredException)。标 @Transactional
        // 让本方法自带 tx, upload / unarchive / setDefault 等所有调用者都安全。
        jpa.deleteByDocumentId(documentId);
        List<ChunkEntity> entities = chunks.stream().map(ChunkMapper::toNewEntity).toList();
        return jpa.saveAll(entities).stream().map(ChunkMapper::toDomain).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public List<Chunk> saveAllAppend(Long documentId, List<Chunk> chunks) {
        // 不清旧。供 Parent-Child 两阶段写入: 先 saveAll(parents) 拿 id, 再用 children 调本方法追加。
        // 调用方需自己保证幂等(整体上层用 deleteByDocumentId 清旧后再两阶段写)。
        // P3-3 fix: 同 saveAll, 标 @Transactional 保证 session 挂上。
        List<ChunkEntity> entities = chunks.stream().map(ChunkMapper::toNewEntity).toList();
        return jpa.saveAll(entities).stream().map(ChunkMapper::toDomain).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteByDocumentId(Long documentId) {
        // P3-3 fix: bulk delete 是 modifying query, 必须在 tx 里。
        // 调用方: DocumentManageService.softDelete (chunks 清除 in-tx) + DocumentManageService.attemptMilvusDelete 不调本方法
        jpa.deleteByDocumentId(documentId);
    }
}
