package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.domain.document.Chunk;
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
    public Optional<Chunk> findByDocumentIdAndSeq(Long documentId, int seq) {
        return jpa.findActiveByDocAndSeq(documentId, seq).map(ChunkMapper::toDomain);
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
}
