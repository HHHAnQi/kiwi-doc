package com.xxx.ragdoc.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DocumentManageService 单测 - 软删 + 重试的状态机边界。
 *
 * <p>Phase 3 / P3-2: shortTx 用匿名子类同步执行 callback, 跳过真实 tx 管理 → 单测无需 DB / DataSource。
 */
class DocumentManageServiceTest {

    private static final ContentHash HASH =
            new ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    /**
     * 同步执行的 shortTx: 不真的开 transaction。直接调 callback.doInTransaction(null) 跑 callback。
     * 重写 execute(...) 而非 executeWithoutResult: 后者是 TransactionOperations 接口的 default method,
     * 内部委托 execute(...); 重写 execute 就堵住了所有路径。
     */
    private static final TransactionTemplate SYNC_SHORT_TX =
            new TransactionTemplate() {
                @Override
                public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                    return action.doInTransaction(null);
                }
            };

    private DocumentRepository documentRepository;
    private ParsingTrigger parsingTrigger;
    private ChunkRepository chunkRepository;
    private VectorStore vectorStore;
    private DocumentManageService manageService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        parsingTrigger = mock(ParsingTrigger.class);
        chunkRepository = mock(ChunkRepository.class);
        vectorStore = mock(VectorStore.class);
        manageService =
                new DocumentManageService(
                        documentRepository,
                        parsingTrigger,
                        chunkRepository,
                        vectorStore,
                        SYNC_SHORT_TX);
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {
        @Test
        @DisplayName("READY 文档可软删: 标 deleted + 删 chunks + 删 Milvus + save 2 次")
        void readyCanDelete() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            manageService.softDelete(1L);

            assertThat(doc.isDeleted()).isTrue();
            assertThat(doc.pendingMilvusDelete()).isFalse(); // Milvus mock 成功 → 同步清标
            // save 调用 2 次: 一次短事务内 chunks+文档 提交 (含 pending=true), 一次 attemptMilvusDelete 成功后清 pending。
            verify(documentRepository, times(2)).save(doc);
            verify(chunkRepository).deleteByDocumentId(1L);
            verify(vectorStore).deleteByDocumentId(1L);
        }

        @Test
        @DisplayName("PARSING 中不可删 → DOC_NOT_FAILED (chunks/Milvus 都不应触发)")
        void parsingCannotDelete() {
            Document doc = parsedDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.softDelete(1L))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FAILED));

            verify(documentRepository, never()).save(any());
            verify(chunkRepository, never()).deleteByDocumentId(any());
            verify(vectorStore, never()).deleteByDocumentId(any());
        }

        @Test
        @DisplayName("不存在 → DOC_NOT_FOUND")
        void missingThrowsNotFound() {
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manageService.softDelete(99L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("Milvus 同步删除失败 → 保留 pending=true, sweeper 后续重试")
        void milvusFailureLeavesPendingFlag() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
            doThrow(new RuntimeException("circuit breaker open"))
                    .when(vectorStore)
                    .deleteByDocumentId(1L);

            manageService.softDelete(1L);

            // 主流程不抛错 (Milvus 失败被吞), doc 已软删 + 标 pending
            assertThat(doc.isDeleted()).isTrue();
            assertThat(doc.pendingMilvusDelete()).isTrue();
            // save 调 1 次: 短事务内的 chunks + pending=true 提交; attemptMilvusDelete 因失败没再 save。
            verify(documentRepository, times(1)).save(doc);
            verify(chunkRepository).deleteByDocumentId(1L);
        }

        @Test
        @DisplayName("attemptMilvusDelete 成功 → 清 pending 并 save, 返回 true")
        void attemptMilvusDeleteSuccess() {
            Document doc = readyDoc(1L);
            // pending=true 模拟"之前 softDelete 已标, 现在重试"
            doc.markPendingMilvusDelete();

            boolean ok = manageService.attemptMilvusDelete(doc);

            assertThat(ok).isTrue();
            assertThat(doc.pendingMilvusDelete()).isFalse();
            verify(vectorStore).deleteByDocumentId(any());
            verify(documentRepository).save(doc);
        }

        @Test
        @DisplayName("attemptMilvusDelete 失败 → 保留 pending 并不 save, 返回 false")
        void attemptMilvusDeleteFailureKeepsPending() {
            Document doc = readyDoc(1L);
            doc.markPendingMilvusDelete();
            doThrow(new RuntimeException("milvus timeout"))
                    .when(vectorStore)
                    .deleteByDocumentId(any());

            boolean ok = manageService.attemptMilvusDelete(doc);

            assertThat(ok).isFalse();
            assertThat(doc.pendingMilvusDelete()).isTrue();
            verify(documentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setDefault")
    class SetDefault {
        @Test
        @DisplayName("READY 文档可设为 default; 无原 default → 仅 mark 新 doc")
        void newDefaultWhenNoExisting() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
            when(documentRepository.findDefaultReadyBySource(doc.source())).thenReturn(Optional.empty());

            manageService.setDefault(1L);

            assertThat(doc.isDefault()).isTrue();
            verify(documentRepository).save(doc);
        }

        @Test
        @DisplayName("已 default 的 READY 文档 → 幂等返回, 不 save")
        void idempotentSetDefault() {
            Document doc = readyDoc(1L);
            doc.markDefault();
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            manageService.setDefault(1L);

            verify(documentRepository, never()).save(any());
        }

        @Test
        @DisplayName("原有别的 default → unmark 旧 + mark 新, 都 save")
        void replacesOldDefault() {
            Document newDoc = readyDoc(1L);
            Document oldDefault = readyDoc(2L);
            oldDefault.markDefault();
            when(documentRepository.findById(1L)).thenReturn(Optional.of(newDoc));
            when(documentRepository.findDefaultReadyBySource(newDoc.source()))
                    .thenReturn(Optional.of(oldDefault));

            manageService.setDefault(1L);

            assertThat(newDoc.isDefault()).isTrue();
            assertThat(oldDefault.isDefault()).isFalse();
            verify(documentRepository).save(oldDefault);
            verify(documentRepository).save(newDoc);
        }

        @Test
        @DisplayName("非 READY 文档 (PARSING) → 409")
        void nonReadyRejects() {
            Document doc = parsedDoc(1L); // status=PARSING
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.setDefault(1L))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FAILED));
            verify(documentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("unarchive")
    class Unarchive {
        @Test
        @DisplayName("已软删文档 → reactivate + 触发 parsingTrigger")
        void unarchiveSoftDeletedDoc() {
            Document doc = readyDoc(1L);
            doc.softDelete();
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            manageService.unarchive(1L);

            assertThat(doc.deleted()).isFalse();
            assertThat(doc.status()).isEqualTo(DocumentStatus.UPLOADED); // reactivate 重置
            assertThat(doc.pendingMilvusDelete()).isFalse(); // reactivate 清 pending
            verify(documentRepository).save(doc);
            verify(parsingTrigger).trigger(1L);
        }

        @Test
        @DisplayName("未删的文档 unarchive → 409")
        void unarchiveNonDeletedRejects() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.unarchive(1L))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FAILED));
            verify(parsingTrigger, never()).trigger(any());
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {
        @Test
        @DisplayName("FAILED 文档可重试一次")
        void failedCanRetryOnce() {
            Document doc = failedDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            manageService.retry(1L);

            assertThat(doc.retryCount()).isEqualTo(1);
            assertThat(doc.status()).isEqualTo(DocumentStatus.PARSING);
            verify(parsingTrigger).trigger(1L);
        }

        @Test
        @DisplayName("READY 不允许重试 → 409")
        void readyCannotRetry() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.retry(1L))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FAILED));

            verify(parsingTrigger, never()).trigger(any());
        }

        @Test
        @DisplayName("Task 4: retry_count=3 (V10 上限) 后再 retry → 409 联系管理员")
        void retryExhausted() {
            // V10 把重试上限从 V1 的 1 放宽到 3 — 已重试 3 次失败, 不再自动重试
            Document doc = failedDocWithRetry(1L, 3);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.retry(1L))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("联系管理员");
        }
    }

    // ===== 构造辅助 =====

    private static Document readyDoc(Long id) {
        Document d = parsedDoc(id);
        d.markChunked(
                List.of(
                        new com.xxx.ragdoc.domain.document.Chunk(
                                1L,
                                id,
                                0,
                                com.xxx.ragdoc.domain.document.ChunkType.TEXT,
                                "x",
                                0,
                                null,
                                null,
                                "h",
                                java.util.List.of())));
        d.markEmbedding();
        d.markIndexing();
        d.markIndexed();
        return d;
    }

    private static Document parsedDoc(Long id) {
        Document d = newDoc(id);
        d.startParsing();
        return d;
    }

    private static Document newDoc(Long id) {
        return Document.restore(
                new DocumentId(id),
                HASH,
                "f.pdf",
                "application/pdf",
                100L,
                "default",
                DocumentStatus.UPLOADED,
                0,
                null,
                List.of(),
                false);
    }

    private static Document failedDoc(Long id) {
        Document d = parsedDoc(id);
        d.markFailed("boom");
        return d;
    }

    private static Document failedDocWithRetry(Long id, int retryCount) {
        return Document.restore(
                new DocumentId(id),
                HASH,
                "f.pdf",
                "application/pdf",
                100L,
                "default",
                DocumentStatus.FAILED,
                retryCount,
                "boom",
                List.of(),
                false);
    }
}
