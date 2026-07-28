package com.xxx.ragdoc.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
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

/** DocumentManageService 单测 - 软删 + 重试的状态机边界。 */
@ExtendWith(MockitoExtension.class)
class DocumentManageServiceTest {

    private static final ContentHash HASH =
            new ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    @Mock private DocumentRepository documentRepository;
    @Mock private ParsingTrigger parsingTrigger;

    @InjectMocks private DocumentManageService manageService;

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {
        @Test
        @DisplayName("READY 文档可软删")
        void readyCanDelete() {
            Document doc = readyDoc(1L);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            manageService.softDelete(1L);

            assertThat(doc.isDeleted()).isTrue();
            verify(documentRepository).save(doc);
        }

        @Test
        @DisplayName("PARSING 中不可删 → DOC_NOT_FAILED")
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
        }

        @Test
        @DisplayName("不存在 → DOC_NOT_FOUND")
        void missingThrowsNotFound() {
            when(documentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manageService.softDelete(99L))
                    .isInstanceOf(NotFoundException.class);
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
        @DisplayName("retry_count=1 后再 retry → 409 联系管理员")
        void retryExhausted() {
            Document doc = failedDocWithRetry(1L, 1);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> manageService.retry(1L))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("联系管理员");
        }
    }

    // ===== 构造辅助 =====

    private static Document readyDoc(Long id) {
        Document d = parsedDoc(id);
        d.markReady(
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
                                "h")));
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
