package com.xxx.ragdoc.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Document 聚合根的不变量测试。覆盖状态机所有合法/非法迁移, 保证领域规则不被手抖破坏。 */
class DocumentTest {

    private static final ContentHash HASH =
            new ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final List<Chunk> SAMPLE_CHUNKS =
            List.of(
                    new Chunk(
                            1L, 1L, 0, ChunkType.TEXT, "hello", 0, null, null, "hash", List.of()));

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

        // ===== F-SMOKE-1: 元数据透传 / 缺省 / 规范化 (防 K2 复活: version 被写死 null) =====

        @Test
        @DisplayName("携带元数据的重载: source/version/language/docType 全部透传不丢")
        void shouldPreserveAllMetadata() {
            Document d =
                    Document.newUploaded(
                            HASH,
                            "f.pdf",
                            "application/pdf",
                            100L,
                            "default",
                            "sentinel",
                            "1.8",
                            "zh",
                            "doc");
            assertThat(d.source()).isEqualTo("sentinel");
            assertThat(d.version()).isEqualTo("1.8"); // ★ 防 K2: 不能再被写死 null
            assertThat(d.language()).isEqualTo("zh");
            assertThat(d.docType()).isEqualTo("doc");
        }

        @Test
        @DisplayName("老重载: 元数据落缺省值(unknown/null/zh/doc) - 防老调用方行为变化")
        void shouldFallBackToDefaultMetadata() {
            Document d = Document.newUploaded(HASH, "f.pdf", "application/pdf", 100L, "default");
            assertThat(d.source()).isEqualTo("unknown");
            assertThat(d.version()).isNull();
            assertThat(d.language()).isEqualTo("zh");
            assertThat(d.docType()).isEqualTo("doc");
        }

        @Test
        @DisplayName("元数据空白串规范化: version 空白→null, language/docType 空白→缺省")
        void shouldNormalizeBlankMetadata() {
            Document d =
                    Document.newUploaded(
                            HASH,
                            "f.pdf",
                            "application/pdf",
                            100L,
                            "default",
                            "  sentinel  ",
                            "   ",
                            "  ",
                            "\t");
            assertThat(d.source()).isEqualTo("sentinel"); // 去首尾空白
            assertThat(d.version()).isNull(); // 空白→null, 不是 "   "
            assertThat(d.language()).isEqualTo("zh");
            assertThat(d.docType()).isEqualTo("doc");
        }
    }

    @Nested
    @DisplayName("工厂方法 restore - 元数据透传")
    class RestoreMetadata {

        @Test
        @DisplayName("restore 带 version=2.0 时 version() 正确返回 2.0")
        void restoreShouldPreserveVersion() {
            Document d =
                    Document.restore(
                            new DocumentId(1L),
                            HASH,
                            "f.pdf",
                            "application/pdf",
                            100L,
                            "default",
                            DocumentStatus.INDEXED,
                            0,
                            null,
                            SAMPLE_CHUNKS,
                            false,
                            "nacos",
                            "2.0",
                            "zh",
                            "spec");
            assertThat(d.source()).isEqualTo("nacos");
            assertThat(d.version()).isEqualTo("2.0"); // ★ 防 K2 在 restore 路径复活
            assertThat(d.docType()).isEqualTo("spec");
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
        @DisplayName("PARSING → CHUNKED: 必须带 chunks")
        void parsingToChunkedRequiresChunks() {
            Document d = parsedDoc();
            assertThatThrownBy(() -> d.markChunked(List.of()))
                    .isInstanceOf(IllegalStateException.class);
            d.markChunked(SAMPLE_CHUNKS);
            assertThat(d.status()).isEqualTo(DocumentStatus.CHUNKED);
        }

        @Test
        @DisplayName("CHUNKED → EMBEDDING → INDEXING → INDEXED: 完整链路推进")
        void chunkedThroughToIndexed() {
            Document d = parsedDoc();
            d.markChunked(SAMPLE_CHUNKS);
            d.markEmbedding();
            assertThat(d.status()).isEqualTo(DocumentStatus.EMBEDDING);
            d.markIndexing();
            assertThat(d.status()).isEqualTo(DocumentStatus.INDEXING);
            d.markIndexed();
            assertThat(d.status()).isEqualTo(DocumentStatus.INDEXED);
        }

        @Test
        @DisplayName("PARSING → FAILED: 必须带 errorMessage")
        void parsingToFailedRequiresMessage() {
            Document d = parsedDoc();
            assertThatThrownBy(() -> d.markFailed(" ")).isInstanceOf(IllegalStateException.class);
            d.markFailed("OCR 失败");
            assertThat(d.status()).isEqualTo(DocumentStatus.FAILED);
            assertThat(d.errorMessage()).isEqualTo("OCR 失败");
        }

        @Test
        @DisplayName("CHUNKED / EMBEDDING / INDEXING 任一可 → FAILED")
        void anyMidStateCanFail() {
            Document chunked = parsedDoc();
            chunked.markChunked(SAMPLE_CHUNKS);
            chunked.markFailed("embed 失败");
            assertThat(chunked.status()).isEqualTo(DocumentStatus.FAILED);

            Document embedding = parsedDoc();
            embedding.markChunked(SAMPLE_CHUNKS);
            embedding.markEmbedding();
            embedding.markFailed("milvus 失败");
            assertThat(embedding.status()).isEqualTo(DocumentStatus.FAILED);
        }

        @Test
        @DisplayName("UPLOADED → CHUNKED 非法 (必须先 PARSING)")
        void uploadedDirectlyToChunkedIsIllegal() {
            Document d = newDoc();
            assertThatThrownBy(() -> d.markChunked(SAMPLE_CHUNKS))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("INDEXED 不允许直接 markChunked 再次")
        void indexedReChunkIllegal() {
            Document d = indexedDoc();
            assertThatThrownBy(() -> d.markChunked(SAMPLE_CHUNKS))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("重试 retry()")
    class Retry {
        @Test
        @DisplayName("FAILED 状态允许重试")
        void retryOnceFromFailed() {
            Document d = failedDoc();
            d.retry();
            assertThat(d.status()).isEqualTo(DocumentStatus.PARSING);
            assertThat(d.retryCount()).isEqualTo(1);
            assertThat(d.errorMessage()).isNull();
        }

        @Test
        @DisplayName("UPLOADED/PARSING 不允许重试")
        void retryOnMidStateFails() {
            assertThatThrownBy(() -> parsedDoc().retry()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> newDoc().retry()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("canRetry() 的语义 (FAILED/INDEXED 且 retryCount < 3)")
        void canRetrySemantics() {
            assertThat(newDoc().canRetry()).isFalse();
            assertThat(failedDoc().canRetry()).isTrue();
            assertThat(parsedDoc().canRetry()).isFalse();
            assertThat(indexedDoc().canRetry()).isTrue();
        }
    }

    @Nested
    @DisplayName("软删除")
    class SoftDelete {
        @Test
        @DisplayName("INDEXED 可软删")
        void indexedCanDelete() {
            Document d = indexedDoc();
            d.softDelete();
            assertThat(d.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("PARSING / 中间态不可删")
        void inFlightCannotDelete() {
            assertThatThrownBy(() -> parsedDoc().softDelete())
                    .isInstanceOf(IllegalStateException.class);
            Document chunked = parsedDoc();
            chunked.markChunked(SAMPLE_CHUNKS);
            assertThatThrownBy(chunked::softDelete).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("已删除的 Document 不能再变更")
        void deletedCannotMutate() {
            Document d = indexedDoc();
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
            Document d =
                    Document.restore(
                            new DocumentId(1L),
                            HASH,
                            "f",
                            "x",
                            1,
                            "t",
                            DocumentStatus.UPLOADED,
                            0,
                            null,
                            List.of(),
                            false);
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

    private static Document indexedDoc() {
        Document d = parsedDoc();
        d.markChunked(SAMPLE_CHUNKS);
        d.markEmbedding();
        d.markIndexing();
        d.markIndexed();
        return d;
    }

    private static Document failedDoc() {
        Document d = parsedDoc();
        d.markFailed("boom");
        return d;
    }
}
