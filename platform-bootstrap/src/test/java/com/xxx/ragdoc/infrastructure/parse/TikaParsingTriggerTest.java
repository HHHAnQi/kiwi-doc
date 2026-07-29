package com.xxx.ragdoc.infrastructure.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.document.chunking.ChunkingService;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * TikaParsingTrigger 单元测试。
 *
 * <p>验证 V2-A 索引管线的状态机契约 + 端口协作: 状态迁移 PARSING→READY/FAILED, chunks 落库, Milvus 写入, 失败兜底。
 * 文本抽取本身(Tika)依赖具体文件, 不在 mock 单测覆盖范围, 由烟测验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TikaParsingTriggerTest {

    private static final long DOC_ID = 100L;
    private static final String VALID_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final byte[] FAKE_BYTES = "fake-pdf-bytes".getBytes();

    @Mock private DocumentRepository documentRepository;
    @Mock private FileStorage fileStorage;
    @Mock private ChunkingService chunkingService;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private ChunkRepository chunkRepository;
    @Mock private VectorStore vectorStore;

    private TikaParsingTrigger trigger;

    /** domain.Chunk 的 64 位 hash 与 entity.id 由 repository 回填, 测试里手工构造模拟。 */
    private static final String CHUNK_HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    @BeforeEach
    void setUp() {
        trigger =
                new TikaParsingTrigger(
                        documentRepository,
                        fileStorage,
                        chunkingService,
                        embeddingClient,
                        chunkRepository,
                        vectorStore);
    }

    /** 构造一个 UPLOADED 状态的 Document(saved 版本, 已有 id)。 */
    private Document newUploadedDoc() {
        return Document.restore(
                new DocumentId(DOC_ID),
                new ContentHash(VALID_HASH),
                "sca.pdf",
                "application/pdf",
                FAKE_BYTES.length,
                "default",
                DocumentStatus.UPLOADED,
                0,
                null,
                List.of(),
                false);
    }

    /** 模拟 ChunkRepository.saveAll 回填 id 后的 chunks。 */
    private List<Chunk> savedChunksWithIds(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(
                        i ->
                                new Chunk(
                                        (long) (i + 1),
                                        DOC_ID,
                                        i,
                                        ChunkType.TEXT,
                                        "chunk-" + i,
                                        0,
                                        null,
                                        null,
                                        CHUNK_HASH))
                .toList();
    }

    // ============================================================
    // Happy path
    // ============================================================

    @Nested
    @DisplayName("Happy path: 全链路成功")
    class HappyPath {

        @Test
        @DisplayName("状态 UPLOADED→PARSING→READY; chunks 落库; Milvus 写入")
        void shouldCompleteFullPipeline() throws Exception {
            Document doc = newUploadedDoc();
            when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
            when(fileStorage.download(any())).thenReturn(FAKE_BYTES);
            when(chunkingService.chunk(any(String.class))).thenReturn(List.of("段一", "段二", "段三"));
            List<EmbeddingResult> embeds =
                    List.of(
                            new EmbeddingResult(new float[1024], Map.of(1, 0.5f)),
                            new EmbeddingResult(new float[1024], Map.of(2, 0.6f)),
                            new EmbeddingResult(new float[1024], Map.of(3, 0.7f)));
            when(embeddingClient.embedBatch(any())).thenReturn(embeds);
            List<Chunk> saved = savedChunksWithIds(3);
            when(chunkRepository.saveAll(eq(DOC_ID), any())).thenReturn(saved);

            trigger.trigger(DOC_ID);

            // 状态机: 先 PARSING 后 READY
            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, times(2)).save(captor.capture());
            Document savedOnce = captor.getValue();
            assertThat(savedOnce.status()).isEqualTo(DocumentStatus.READY);
            assertThat(savedOnce.chunks()).hasSize(3);

            // chunks 表写入
            verify(chunkRepository).saveAll(eq(DOC_ID), any());
            // Milvus: 先删(重新解析幂等) + 写
            verify(vectorStore).deleteByDocumentId(DOC_ID);
            verify(vectorStore).upsertChunks(eq(DOC_ID), eq(saved), eq(embeds));
        }

        @Test
        @DisplayName("objectKey 按 raw/{docId}/{filename} 规则下载")
        void shouldDownloadWithConventionObjectKey() throws Exception {
            Document doc = newUploadedDoc();
            when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
            when(fileStorage.download(any())).thenReturn(FAKE_BYTES);
            when(chunkingService.chunk(any(String.class))).thenReturn(List.of("a-chunk-text"));
            when(embeddingClient.embedBatch(any()))
                    .thenReturn(List.of(new EmbeddingResult(new float[1024], Map.of(1, 0.1f))));
            when(chunkRepository.saveAll(eq(DOC_ID), any())).thenReturn(savedChunksWithIds(1));

            trigger.trigger(DOC_ID);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(fileStorage).download(keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo("raw/100/sca.pdf");
        }
    }

    // ============================================================
    // Failure paths
    // ============================================================

    @Nested
    @DisplayName("失败路径: 任一环节失败必须 markFailed + 重抛")
    class FailurePaths {

        @Test
        @DisplayName("下载失败 → markFailed + 重抛 IllegalStateException")
        void downloadFailureMarksDocFailed() throws Exception {
            Document doc = newUploadedDoc();
            when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
            when(fileStorage.download(any())).thenThrow(new java.io.IOException("MinIO down"));

            assertThatThrownBy(() -> trigger.trigger(DOC_ID))
                    .isInstanceOf(IllegalStateException.class);

            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, atLeastOnce()).save(captor.capture());
            // 至少一次是 FAILED
            assertThat(captor.getAllValues())
                    .anyMatch(d -> d.status() == DocumentStatus.FAILED && d.errorMessage() != null);

            // chunks 表不应被写
            verify(chunkRepository, org.mockito.Mockito.never()).saveAll(anyLong(), any());
            // Milvus 不应被写
            verify(vectorStore, org.mockito.Mockito.never()).upsertChunks(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Tika 抽出空文本(纯图 PDF 等) → markFailed")
        void emptyTextMarksDocFailed() throws Exception {
            Document doc = newUploadedDoc();
            when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
            when(fileStorage.download(any())).thenReturn(FAKE_BYTES);
            // parseToString 抽出空白
            // 为了让"全文为空"路径触发, 让 chunkingService 收到空字符串切片结果为空
            when(chunkingService.chunk(any(String.class))).thenReturn(List.of());

            assertThatThrownBy(() -> trigger.trigger(DOC_ID))
                    .isInstanceOf(IllegalStateException.class);

            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(d -> d.status() == DocumentStatus.FAILED);

            verify(chunkRepository, org.mockito.Mockito.never()).saveAll(anyLong(), any());
            verify(vectorStore, org.mockito.Mockito.never()).upsertChunks(anyLong(), any(), any());
        }

        @Test
        @DisplayName("embedBatch 数量与 chunks 不一致 → markFailed (数据一致性问题)")
        void embeddingCountMismatchMarksDocFailed() throws Exception {
            Document doc = newUploadedDoc();
            when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
            when(fileStorage.download(any())).thenReturn(FAKE_BYTES);
            when(chunkingService.chunk(any(String.class)))
                    .thenReturn(List.of("chunk-a", "chunk-b"));
            // 切了 2 段, 但 embedding 只返回 1 个 → 不一致
            when(embeddingClient.embedBatch(any()))
                    .thenReturn(List.of(new EmbeddingResult(new float[1024], Map.of(1, 0.2f))));

            assertThatThrownBy(() -> trigger.trigger(DOC_ID))
                    .isInstanceOf(IllegalStateException.class);

            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(d -> d.status() == DocumentStatus.FAILED);

            // embed 阶段失败了, 没机会写库/写向量
            verify(chunkRepository, org.mockito.Mockito.never()).saveAll(anyLong(), any());
            verify(vectorStore, org.mockito.Mockito.never()).upsertChunks(anyLong(), any(), any());
        }
    }
}
