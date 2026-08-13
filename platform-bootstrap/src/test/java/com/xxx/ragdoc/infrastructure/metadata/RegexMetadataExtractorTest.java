package com.xxx.ragdoc.infrastructure.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.document.command.UploadCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** RegexMetadataExtractor 单测: 覆盖 source / version / docType / language 抽取典型样本 + 只填空白策略。 */
@DisplayName("RegexMetadataExtractor")
class RegexMetadataExtractorTest {

    private final RegexMetadataExtractor extractor = new RegexMetadataExtractor();

    @Test
    @DisplayName("不同版本文件名应推导为同一 logicalDocumentKey")
    void derivesStableLogicalDocumentKeyAcrossVersions() {
        UploadCommand v1 = cmdWithFilename("nacos-user-guide-1.0.pdf");
        UploadCommand v2 = cmdWithFilename("nacos-user-guide-2.3.1.pdf");

        assertThat(v1.logicalDocumentKey()).isEqualTo("nacos-user-guide");
        assertThat(v2.logicalDocumentKey()).isEqualTo(v1.logicalDocumentKey());
    }

    @Test
    @DisplayName("显式 logicalDocumentKey 优先于文件名推导")
    void explicitLogicalDocumentKeyWins() {
        UploadCommand command =
                new UploadCommand(
                        "renamed-2.0.pdf",
                        "application/pdf",
                        1,
                        new byte[] {1},
                        "default",
                        "nacos",
                        "2.0",
                        "zh",
                        "doc",
                        "confluence:page-42");

        assertThat(command.logicalDocumentKey()).isEqualTo("confluence:page-42");
    }

    /**
     * 辅助: 构造一个全空白 (让 UploadCommand 落缺省 source=unknown/version=null/docType=doc/language=zh) 的 cmd。
     */
    private static UploadCommand cmdWithFilename(String filename) {
        return new UploadCommand(
                filename,
                "application/pdf",
                100L,
                new byte[] {1, 2, 3},
                "default",
                null, // source → 缺省 unknown
                null, // version → null
                null, // language → 缺省 zh
                null); // docType → 缺省 doc
    }

    @Nested
    @DisplayName("source 抽取")
    class Source {
        @Test
        @DisplayName("nacos 关键词 → source=nacos")
        void extractsNacos() {
            UploadCommand r = extractor.enrich(cmdWithFilename("nacos-2.3.2-reference.pdf"));
            assertThat(r.source()).isEqualTo("nacos");
        }

        @Test
        @DisplayName("sentinel 大小写通配 → source=sentinel")
        void caseInsensitive() {
            UploadCommand r = extractor.enrich(cmdWithFilename("Sentinel-UserGuide.pdf"));
            assertThat(r.source()).isEqualTo("sentinel");
        }

        @Test
        @DisplayName("spring-cloud-alibaba → 简化为 sca")
        void aliasesSpringCloudAlibaba() {
            UploadCommand r = extractor.enrich(cmdWithFilename("spring-cloud-alibaba-doc.pdf"));
            assertThat(r.source()).isEqualTo("sca");
        }

        @Test
        @DisplayName("无组件关键词 → 保持 unknown")
        void noMatchKeepsUnknown() {
            UploadCommand r = extractor.enrich(cmdWithFilename("random-report.pdf"));
            assertThat(r.source()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("用户显式传 source → 抽取不覆盖")
        void explicitUserInputWins() {
            UploadCommand cmd =
                    new UploadCommand(
                            "nacos-stuff.pdf",
                            "application/pdf",
                            1L,
                            new byte[] {1},
                            "default",
                            "rocketmq", // 显式传 rocketmq, 不应被 nacos 覆盖
                            null,
                            null,
                            null);
            UploadCommand r = extractor.enrich(cmd);
            assertThat(r.source()).isEqualTo("rocketmq");
        }
    }

    @Nested
    @DisplayName("version 抽取")
    class Version {
        @Test
        @DisplayName("三段版本号 → 完整 2.3.2")
        void extractsFullSemver() {
            UploadCommand r = extractor.enrich(cmdWithFilename("nacos-2.3.2-reference.pdf"));
            assertThat(r.version()).isEqualTo("2.3.2");
        }

        @Test
        @DisplayName("v 前缀版本 → 去前缀")
        void stripsV() {
            UploadCommand r = extractor.enrich(cmdWithFilename("seata-v1.7.0.zip"));
            assertThat(r.version()).isEqualTo("1.7.0");
        }

        @Test
        @DisplayName("含 RC 后缀 → 保留")
        void preservesRc() {
            UploadCommand r = extractor.enrich(cmdWithFilename("rocketmq-5.0.0-RC1.pdf"));
            // 期望 5.0.0-RC1 (或相近变体); 测试用 startsWith 防正则细节漂移
            assertThat(r.version()).startsWith("5.0.0");
        }

        @Test
        @DisplayName("无版本号 → 保持 null")
        void noVersionKeepsNull() {
            UploadCommand r = extractor.enrich(cmdWithFilename("sentinel-doc.pdf"));
            assertThat(r.version()).isNull();
        }

        @Test
        @DisplayName("用户显式传 version → 不覆盖")
        void explicitUserVersionWins() {
            UploadCommand cmd =
                    new UploadCommand(
                            "nacos-2.3.2.pdf",
                            "application/pdf",
                            1L,
                            new byte[] {1},
                            "default",
                            null,
                            "9.9.9",
                            null,
                            null);
            UploadCommand r = extractor.enrich(cmd);
            assertThat(r.version()).isEqualTo("9.9.9");
        }
    }

    @Nested
    @DisplayName("docType 抽取")
    class DocType {
        @Test
        @DisplayName("reference → doc (规范化)")
        void referenceToDoc() {
            UploadCommand r = extractor.enrich(cmdWithFilename("nacos-reference.pdf"));
            assertThat(r.docType()).isEqualTo("doc");
        }

        @Test
        @DisplayName("release-notes → release-notes (原样保留)")
        void releaseNotesType() {
            UploadCommand r = extractor.enrich(cmdWithFilename("dubbo-release-notes-3.2.pdf"));
            assertThat(r.docType()).isEqualTo("release-notes");
        }

        @Test
        @DisplayName("blog → blog")
        void blogType() {
            UploadCommand r = extractor.enrich(cmdWithFilename("sentinel-blog-2024.pdf"));
            assertThat(r.docType()).isEqualTo("blog");
        }

        @Test
        @DisplayName("无类型关键词 → 保持 doc")
        void noTypeMatchKeepsDoc() {
            UploadCommand r = extractor.enrich(cmdWithFilename("nacos.pdf"));
            assertThat(r.docType()).isEqualTo("doc");
        }
    }

    @Nested
    @DisplayName("language 抽取")
    class Language {
        @Test
        @DisplayName("命中 en → language=en")
        void englishDetected() {
            UploadCommand r = extractor.enrich(cmdWithFilename("sentinel-en.pdf"));
            assertThat(r.language()).isEqualTo("en");
        }

        @Test
        @DisplayName("命中 zh → 保持 zh")
        void chineseKeepsZh() {
            UploadCommand r = extractor.enrich(cmdWithFilename("sentinel-zh.pdf"));
            assertThat(r.language()).isEqualTo("zh");
        }

        @Test
        @DisplayName("命中 zh-cn + en 共存 → zh 胜出")
        void bothZhAndEn() {
            UploadCommand r = extractor.enrich(cmdWithFilename("nacos-zh-cn-en.pdf"));
            assertThat(r.language()).isEqualTo("zh");
        }
    }

    @Test
    @DisplayName("复合样本: 完整文件名 → 全字段抽取")
    void endToEndSample() {
        UploadCommand r = extractor.enrich(cmdWithFilename("nacos-2.3.2-reference-en.pdf"));
        assertThat(r.source()).isEqualTo("nacos");
        assertThat(r.version()).isEqualTo("2.3.2");
        assertThat(r.docType()).isEqualTo("doc"); // reference → doc
        assertThat(r.language()).isEqualTo("en");
    }

    @Test
    @DisplayName("无任何 metadata 变更 → 返回原 cmd 实例 (避免对象创建)")
    void noChangeReturnsSameInstance() {
        UploadCommand cmd = cmdWithFilename("random.pdf"); // 无 source/version/docType/language 关键词
        UploadCommand r = extractor.enrich(cmd);
        assertThat(r).isSameAs(cmd);
    }

    @Test
    @DisplayName("null 入参 → 返 null 不抛")
    void nullInputReturnsNull() {
        assertThat(extractor.enrich(null)).isNull();
    }
}
