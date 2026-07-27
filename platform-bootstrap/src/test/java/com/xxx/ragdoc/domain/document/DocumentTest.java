package com.xxx.ragdoc.domain.document;

import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document 聚合根的不变量测试。覆盖状态机所有合法/非法迁移,
 * 保证领域规则不被手抖破坏。
 */
class DocumentTest {

    private static final ContentHash HASH = new ContentHash(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final List<Chunk> SAMPLE_CHUNKS = List.of(
            new Chunk(1L, 1L, 0, ChunkType.TEXT, "hello", 0, null, null, "hash"));

    @Nested
    @DisplayName("工厂方法 newUploaded")
    class NewUploaded {
        @Test
        @DisplayName("正常构造: 状态为 UPLOADED, retryCount=0, 未删除")
        void shouldCreateWithDefaults() {
            Document d = Document.newUploaded(HASH, "f.pdf", "application/pdf", 100L, "default");
            assertThat(d.status()).isEqualTo(DocumentStatus.UPLOADED);
            assertThat(d.retryCount()).isZero();
            assertThat(d.isDeleted()).isFalse();
            assertThat(d.id()).isNull();
        }

        @Test
        @DisplayName("hash/filename/mimeType 不能为 null")
        void shouldRejectNullFields() {
            assertThatThrownBy(() -> Document.newUploaded(null, "f", "x", 1, "t"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Document.newUploaded(HASH, null, "x", 1, "t"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("sizeBytes 不能为负")
        void shouldRejectNegativeSize() {
            assertThatThrownBy(() -> Document.newUploaded(HASH, "f", "x", -1, "t"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("状态机迁移")
    class StateMachine {

        @Test
        @DisplayName("UPLOADED → PARSING 合法")
        void uploadedToParsing() {
            Document d = newDoc();
            d.startParsing();
            assertThat(d.status()).isEqualTo(DocumentStatus.PARSING);
        }

        @Test
        @DisplayName("PARSING → READY: 必须带 chunks")
        void parsingToReadyRequiresChunks() {
            Document d = parsedDoc();
            assertThatThrownBy(() -> d.markReady(List.of()))
                    .isInstanceOf(IllegalStateException.class);
            d.markReady(SAMPLE_CHUNKS);
            assertThat(d.status()).isEqualTo(DocumentStatus.READY);
        }

        @Test
        @DisplayName("PARSING → FAILED: 必须带 errorMessage")
        void parsingToFailedRequiresMessage() {
            Document d = parsedDoc();
            assertThatThrownBy(() -> d.markFailed(" "))
                    .isInstanceOf(IllegalStateException.class);
            d.markFailed("OCR 失败");
            assertThat(d.status()).isEqualTo(DocumentStatus.FAILED);
            assertThat(d.errorMessage()).isEqualTo("OCR 失败");
        }

        @Test
        @DisplayName("UPLOADED → READY 非法")
        void uploadedDirectlyToReadyIsIllegal() {
            Document d = newDoc();
            assertThatThrownBy(() -> d.markReady(SAMPLE_CHUNKS))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("READY 不允许直接 markReady 再次")
        void readyToReadyIllegal() {
            Document d = readyDoc();
            assertThatThrownBy(() -> d.markReady(SAMPLE_CHUNKS))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("重试 retry()")
    class Retry {
        @Test
        @DisplayName("FAILED 状态允许重试一次")
        void retryOnceFromFailed() {
            Document d = failedDoc();
            d.retry();
            assertThat(d.status()).isEqualTo(DocumentStatus.PARSING);
            assertThat(d.retryCount()).isEqualTo(1);
            assertThat(d.errorMessage()).isNull();
        }

        @Test
        @DisplayName("READY 不允许重试")
        void retryOnReadyFails() {
            Document d = readyDoc();
            assertThatThrownBy(d::retry).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("canRetry() 的语义")
        void canRetrySemantics() {
            assertThat(newDoc().canRetry()).isFalse();
            assertThat(failedDoc().canRetry()).isTrue();
            assertThat(parsedDoc().canRetry()).isFalse();
        }
    }

    @Nested
    @DisplayName("软删除")
    class SoftDelete {
        @Test
        @DisplayName("READY 可软删")
        void readyCanDelete() {
            Document d = readyDoc();
            d.softDelete();
            assertThat(d.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("PARSING 中不可删")
        void parsingCannotDelete() {
            Document d = parsedDoc();
            assertThatThrownBy(d::softDelete).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("已删除的 Document 不能再变更")
        void deletedCannotMutate() {
            Document d = readyDoc();
            d.softDelete();
            assertThatThrownBy(d::retry).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(d::startParsing).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("assignId")
    class AssignId {
        @Test
        @DisplayName("首次 assign 合法")
        void assignFirst() {
            Document d = newDoc();
            d.assignId(new DocumentId(42L));
            assertThat(d.id().value()).isEqualTo(42L);
        }

        @Test
        @DisplayName("重复 assign 非法")
        void cannotReassign() {
            Document d = Document.restore(
                    new DocumentId(1L), HASH, "f", "x", 1, "t",
                    DocumentStatus.UPLOADED, 0, null, List.of(), false);
            assertThatThrownBy(() -> d.assignId(new DocumentId(2L)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ===== 辅助构造 =====

    private static Document newDoc() {
        return Document.newUploaded(HASH, "f.pdf", "application/pdf", 100L, "default");
    }

    private static Document parsedDoc() {
        Document d = newDoc();
        d.startParsing();
        return d;
    }

    private static Document readyDoc() {
        Document d = parsedDoc();
        d.markReady(SAMPLE_CHUNKS);
        return d;
    }

    private static Document failedDoc() {
        Document d = parsedDoc();
        d.markFailed("boom");
        return d;
    }
}
