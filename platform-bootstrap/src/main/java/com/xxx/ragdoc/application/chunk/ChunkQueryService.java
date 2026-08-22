package com.xxx.ragdoc.application.chunk;

import com.xxx.ragdoc.application.chunk.query.ChunkDetail;
import com.xxx.ragdoc.application.chunk.query.ChunkNeighbors;
import com.xxx.ragdoc.application.chunk.query.PageChunks;
import com.xxx.ragdoc.application.document.DocumentAccessGuard;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk 查询用例(读路径: 单条 / 相邻 / 按页)。
 *
 * <p>引用关系: 上传文档 → 解析产 chunks(V2 接入) → 引用来源 → chunk 详情。 V1 parsing 是 stub, 数据库实际无 chunks,
 * 故所有查询都"优雅降级":
 *
 * <ul>
 *   <li>单条不存在 → 404 CHUNK_NOT_FOUND
 *   <li>相邻不存在 → null(不报错)
 *   <li>按页不存在 → 空数组(不报错)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkQueryService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final DocumentAccessGuard documentAccessGuard;

    /** 单条 chunk 详情(含父文档 filename)。 */
    @Transactional(readOnly = true)
    public ChunkDetail getChunk(Long chunkId) {
        Chunk chunk =
                chunkRepository
                        .findById(chunkId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CHUNK_NOT_FOUND,
                                                "chunk 不存在或所属文档已删除: " + chunkId));
        // chunk 内容属于文档主体数据: 与 GET /documents/{id} 同档守门(tenant + ACL + visibility)。
        // 缺失时任意 token 持有者可通过枚举 chunkId 读任意租户文档全文。
        documentAccessGuard.requireRead(chunk.documentId());
        String filename = documentFilename(chunk.documentId());
        return ChunkDetail.from(chunk, filename);
    }

    /** 相邻 chunks(direction ∈ {prev, next, both})。 边界情况返回对应字段 null。 */
    @Transactional(readOnly = true)
    public ChunkNeighbors getNeighbors(Long chunkId, Direction direction) {
        Chunk current =
                chunkRepository
                        .findById(chunkId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CHUNK_NOT_FOUND,
                                                "chunk 不存在或所属文档已删除: " + chunkId));

        ChunkDetail prev = null;
        ChunkDetail next = null;
        documentAccessGuard.requireRead(current.documentId());
        String filename = documentFilename(current.documentId());
        // V3 parent-child 模式: 同 (docId, seq) 可能同时有 PARENT/CHILD, 必须按当前
        // chunk 的 type 过滤相邻, 否则 JPA getSingleResult 抛 NonUniqueResultException。
        com.xxx.ragdoc.domain.document.ChunkType t = current.type();

        if (direction == Direction.PREV || direction == Direction.BOTH) {
            prev =
                    chunkRepository
                            .findByDocumentIdAndSeq(current.documentId(), current.seq() - 1, t)
                            .map(c -> ChunkDetail.from(c, filename))
                            .orElse(null);
        }
        if (direction == Direction.NEXT || direction == Direction.BOTH) {
            next =
                    chunkRepository
                            .findByDocumentIdAndSeq(current.documentId(), current.seq() + 1, t)
                            .map(c -> ChunkDetail.from(c, filename))
                            .orElse(null);
        }
        return new ChunkNeighbors(prev, next);
    }

    /** 拉取某 doc 某页全部 chunks(按 seq 升序)。 优先校验 doc 存在+可读(不存在/无权 → DOC_NOT_FOUND); 无 chunks → 返回空数组。 */
    @Transactional(readOnly = true)
    public PageChunks listByPage(Long documentId, int page) {
        // 守门含存在性校验: 不存在或当前用户不可读 → 404 (与文档详情接口同语义, 防枚举)
        documentAccessGuard.requireRead(documentId);

        List<Chunk> chunks = chunkRepository.findByDocumentIdAndPageOrderBySeq(documentId, page);
        if (chunks.isEmpty()) {
            return PageChunks.empty(page);
        }
        String filename = documentFilename(documentId);
        int totalPagesInDoc = chunkRepository.maxPageOfDocument(documentId);
        List<ChunkDetail> items = chunks.stream().map(c -> ChunkDetail.from(c, filename)).toList();
        return new PageChunks(items, page, totalPagesInDoc);
    }

    private String documentFilename(Long docId) {
        return documentRepository
                .findDetailById(docId)
                .map(d -> d.originalFilename())
                .orElse("(unknown)");
    }

    /** 相邻查询方向枚举。 */
    public enum Direction {
        PREV,
        NEXT,
        BOTH;

        public static Direction parse(String value) {
            if (value == null || value.isBlank()) {
                return BOTH;
            }
            return valueOf(value.trim().toUpperCase());
        }
    }
}
