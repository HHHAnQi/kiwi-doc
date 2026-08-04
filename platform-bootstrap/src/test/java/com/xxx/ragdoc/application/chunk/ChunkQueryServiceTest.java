package com.xxx.ragdoc.application.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chunk.query.ChunkDetail;
import com.xxx.ragdoc.application.chunk.query.ChunkNeighbors;
import com.xxx.ragdoc.application.chunk.query.PageChunks;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.BoundingBox;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChunkQueryService 单测。
 *
 * <p>关键场景:
 *
 * <ul>
 *   <li>单条不存在 → CHUNK_NOT_FOUND
 *   <li>相邻边界不存在 → null(不报错)
 *   <li>按页 doc 不存在 → DOC_NOT_FOUND
 *   <li>软删过滤其实已被 JPA 查询兜底, 此处不再 mock repeat
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChunkQueryServiceTest {

    @Mock private ChunkRepository chunkRepository;
    @Mock private DocumentRepository documentRepository;

    @InjectMocks private ChunkQueryService service;

    private static Chunk sampleChunk(Long id, Long docId, int seq) {
        return new Chunk(
                id,
                docId,
                seq,
                ChunkType.TEXT,
                "content-" + seq,
                1,
                new BoundingBox(0, 0, 1, 1),
                null,
                "hash-" + seq,
                List.of());
    }

    private static Document sampleDoc(Long id) {
        return Document.restore(
                new DocumentId(id),
                new ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "f.pdf",
                "application/pdf",
                100L,
                "default",
                DocumentStatus.READY,
                0,
                null,
                List.of(),
                false);
    }

    private void mockDocDetail(Long docId) {
        DocumentDetail detail =
                new DocumentDetail(
                        docId,
                        "f.pdf",
                        "application/pdf",
                        DocumentStatus.READY,
                        100,
                        5,
                        0,
                        null,
                        null,
                        null,
                        "unknown",
                        null,
                        "zh",
                        "doc",
                        false, // isDefault (P3-1)
                        false); // pendingMilvusDelete (P3-2)
        when(documentRepository.findDetailById(docId)).thenReturn(Optional.of(detail));
    }

    @Nested
    @DisplayName("getChunk 单条")
    class GetChunk {
        @Test
        @DisplayName("存在 → 返回详情(含 documentFilename)")
        void existingReturnsDetail() {
            Chunk c = sampleChunk(10L, 1L, 5);
            when(chunkRepository.findById(10L)).thenReturn(Optional.of(c));
            mockDocDetail(1L);

            ChunkDetail d = service.getChunk(10L);

            assertThat(d.id()).isEqualTo(10L);
            assertThat(d.documentId()).isEqualTo(1L);
            assertThat(d.documentFilename()).isEqualTo("f.pdf");
        }

        @Test
        @DisplayName("不存在 → CHUNK_NOT_FOUND")
        void missingThrows404() {
            when(chunkRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getChunk(99L))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((NotFoundException) ex).errorCode())
                                            .isEqualTo(ErrorCode.CHUNK_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getNeighbors 相邻")
    class GetNeighbors {
        @Test
        @DisplayName("中间 chunk: prev 和 next 都有")
        void middleChunk() {
            Chunk cur = sampleChunk(5L, 1L, 5);
            Chunk prev = sampleChunk(4L, 1L, 4);
            Chunk next = sampleChunk(6L, 1L, 6);
            when(chunkRepository.findById(5L)).thenReturn(Optional.of(cur));
            when(chunkRepository.findByDocumentIdAndSeq(1L, 4, ChunkType.TEXT)).thenReturn(Optional.of(prev));
            when(chunkRepository.findByDocumentIdAndSeq(1L, 6, ChunkType.TEXT)).thenReturn(Optional.of(next));
            mockDocDetail(1L);

            ChunkNeighbors n = service.getNeighbors(5L, ChunkQueryService.Direction.BOTH);

            assertThat(n.prev().seq()).isEqualTo(4);
            assertThat(n.next().seq()).isEqualTo(6);
        }

        @Test
        @DisplayName("第一条 chunk: prev=null 不报错")
        void firstChunkPrevNull() {
            Chunk cur = sampleChunk(1L, 1L, 0);
            when(chunkRepository.findById(1L)).thenReturn(Optional.of(cur));
            when(chunkRepository.findByDocumentIdAndSeq(1L, -1, ChunkType.TEXT)).thenReturn(Optional.empty());
            mockDocDetail(1L);

            ChunkNeighbors n = service.getNeighbors(1L, ChunkQueryService.Direction.BOTH);

            assertThat(n.prev()).isNull();
        }

        @Test
        @DisplayName("direction=PREV 时不查 next")
        void prevOnly() {
            Chunk cur = sampleChunk(5L, 1L, 5);
            when(chunkRepository.findById(5L)).thenReturn(Optional.of(cur));
            when(chunkRepository.findByDocumentIdAndSeq(1L, 4, ChunkType.TEXT)).thenReturn(Optional.empty());
            mockDocDetail(1L);

            ChunkNeighbors n = service.getNeighbors(5L, ChunkQueryService.Direction.PREV);

            assertThat(n.next()).isNull();
            verify(chunkRepository, never()).findByDocumentIdAndSeq(eq(1L), eq(6), eq(ChunkType.TEXT));
        }

        @Test
        @DisplayName("当前 chunk 不存在 → CHUNK_NOT_FOUND")
        void currentMissing() {
            when(chunkRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getNeighbors(99L, ChunkQueryService.Direction.BOTH))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((NotFoundException) ex).errorCode())
                                            .isEqualTo(ErrorCode.CHUNK_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("listByPage 按页")
    class ListByPage {
        @Test
        @DisplayName("doc 不存在 → DOC_NOT_FOUND")
        void docMissing() {
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listByPage(99L, 1))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((NotFoundException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FOUND));
        }

        @Test
        @DisplayName("doc 存在但该页无 chunks → 返回空数组")
        void docExistsButNoChunks() {
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sampleDoc(1L)));
            when(chunkRepository.findByDocumentIdAndPageOrderBySeq(1L, 3)).thenReturn(List.of());

            PageChunks p = service.listByPage(1L, 3);

            assertThat(p.chunks()).isEmpty();
            assertThat(p.totalPagesInDoc()).isZero();
        }

        @Test
        @DisplayName("有 chunks → 按 seq 升序, 含 totalPagesInDoc")
        void withChunks() {
            when(documentRepository.findById(1L)).thenReturn(Optional.of(sampleDoc(1L)));
            Chunk c1 = sampleChunk(1L, 1L, 0);
            Chunk c2 = sampleChunk(2L, 1L, 1);
            when(chunkRepository.findByDocumentIdAndPageOrderBySeq(1L, 1))
                    .thenReturn(List.of(c1, c2));
            when(chunkRepository.maxPageOfDocument(1L)).thenReturn(10);
            mockDocDetail(1L);

            PageChunks p = service.listByPage(1L, 1);

            assertThat(p.chunks()).hasSize(2);
            assertThat(p.totalPagesInDoc()).isEqualTo(10);
        }
    }
}
