package com.xxx.ragdoc.infrastructure.parse;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.SecurityScannerProperties;
import com.xxx.ragdoc.application.document.chunking.ChunkingProperties;
import com.xxx.ragdoc.application.document.chunking.ChunkingService;
import com.xxx.ragdoc.application.document.ingestion.IngestionPolicy;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.DocumentStatePort;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.security.ScanResult;
import com.xxx.ragdoc.application.document.security.port.SecurityScannerPort;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V2 真解析触发器: 同步走完整条 RAG 索引链路。
 *
 * <p>流程(架构师视角: 每一步都是可独立替换的端口):
 *
 * <ol>
 *   <li>加载 Document 聚合, 迁移到 PARSING
 *   <li>从 MinIO 下载原始文件(objectKey 约定: raw/{docId}/{filename})
 *   <li>Apache Tika 抽取全文(PDF / Markdown / HTML / 纯文本统一入口)
 *   <li>{@link ChunkingService} 切片(token-based, 边界优先换行/句号)
 *   <li>构建 domain.Chunk 列表(content_hash SHA-256)
 *   <li>{@link EmbeddingClient#embedBatch} 批量生成 dense+sparse 向量
 *   <li>{@link ChunkRepository#saveAll} 落库(含清旧, 幂等)
 *   <li>{@link VectorStore#upsertChunks} 写 Milvus
 *   <li>doc.markChunked(savedChunks); doc.markEmbedding(); doc.markIndexing(); doc.markIndexed(); {
 *       tmp = doc.markReady(chunks) 状态机迁移到 READY
 * </ol>
 *
 * <p>异常处理: 任一步失败 → markFailed 并落库; 传播异常给上游 DocumentUploadService 决策(是否回滚/重试)。 资源安全(Scanner/流)不在此管:
 * Tika 内部已处理; 字节缓冲一次性读入。<br>
 * V3 演进: 替换为发 DocumentParsedRequest 到 MQ, 由独立 parser-service 异步消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
// V3 spec §2.3: rag.parser.mode 默认 sync, 走本同步实现; async 时改为发 MQ.
@ConditionalOnProperty(
        prefix = "rag.parser",
        name = "mode",
        havingValue = "sync",
        matchIfMissing = true)
public class TikaParsingTrigger implements ParsingTrigger {

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final ChunkingProperties chunkingProps;

    /**
     * Task 4 / V10: 中间态推进端口 (CHUNKED / EMBEDDING / INDEXING / INDEXED)。 让 reconcile job 能识别
     * in-flight 阶段并扫卡死。可选注入 — 缺省 (测试) 时仅推进内存聚合根, 不持久化中间态, 但终端 markIndexed 仍由外层 trigger +
     * documentRepository.save 兜底。
     */
    private final DocumentStatePort statePort;

    /**
     * Task 8 / V14 RAG Security: 文档级 prompt-injection scanner。 可选注入 — bean 默认 always-on (无
     * ConditionalOnProperty), 内部 properties.isEnabled() 决定真扫; 测试可传 null 走老路径兼容。
     */
    private final SecurityScannerPort securityScanner;

    /** Task 8: scanner 配置 (always injected, 默认 disabled)。 */
    private final SecurityScannerProperties securityScannerProperties;

    /** 同步/异步共用的脱敏与质量门禁。 */
    private final IngestionPolicy ingestionPolicy;

    /** Tika 是线程安全(内部 facade 单例), 复用即可。 */
    private final Tika tika = new Tika();

    /**
     * P1 Contextual Retrieval: embed 输入 = 确定性上下文前缀 + chunk 原文。
     * 只影响向量; chunk.content/哈希/Milvus BM25 文本保持原文。flag 关闭时 = baseline。
     */
    private java.util.List<String> contextualEmbedInputs(
            Document doc, java.util.List<Chunk> chunks) {
        java.util.function.Function<Chunk, String> toInput =
                chunkingProps.isContextualPrefixEnabled()
                        ? c ->
                                com.xxx.ragdoc.application.document.chunking
                                        .ContextualEmbeddingPrefix.build(
                                        doc.originalFilename(),
                                        doc.source(),
                                        c.sectionPath(),
                                        chunkingProps.getContextualPrefixMaxChars())
                                + c.content()
                        : Chunk::content;
        return chunks.stream().map(toInput).toList();
    }

    private static final int MAX_TEXT_LENGTH = 5_000_000; // 5M 字符上限保护, 防超大文件 OOM

    @Override
    public void trigger(Long documentId) {
        Document doc =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(
                                () -> new IllegalStateException("Document 不存在: " + documentId));
        try {
            // 1. 状态机: UPLOADED → PARSING(markReady 之前任何异常都视为失败)
            doc.startParsing();
            documentRepository.save(doc);
            log.info("parse.start doc_id={}, filename={}", documentId, doc.originalFilename());

            // 2. 下载原始文件(objectKey 与 MinioFileStorage.uploadRaw 的 key 规则对齐)
            String objectKey = buildObjectKey(doc);
            byte[] bytes = fileStorage.download(objectKey);
            log.debug("parse.downloaded doc_id={}, size={}", documentId, bytes.length);

            // 3. Tika 抽取全文
            String fullText = extractText(doc, bytes);
            if (fullText == null || fullText.isBlank()) {
                throw new IllegalStateException("Tika 抽取文本为空");
            }
            if (fullText.length() > MAX_TEXT_LENGTH) {
                log.warn(
                        "parse.text_truncated doc_id={}, raw_len={}",
                        documentId,
                        fullText.length());
                fullText = fullText.substring(0, MAX_TEXT_LENGTH);
            }

            int redactionCount = 0;
            if (ingestionPolicy != null) {
                IngestionPolicy.PreparedText prepared =
                        ingestionPolicy.prepareText(documentId, fullText);
                fullText = prepared.text();
                redactionCount = prepared.redactionCount();
            }

            // ============ Task 8 / V14 Security Scan (防 prompt injection) ============
            // 在 chunk 前单次 document-level scan, MALICIOUS 时 markFailed + 抛, 不进 chunk;
            // SUSPICIOUS 仅 TAG 不阻 (灰度观察); CLEAN 自然通过。
            // 详见 docs 里的 defense-in-depth: scanner 是第一道, citation-verifier (Task 7) 二道。
            if (securityScanner != null && securityScannerProperties != null) {
                try {
                    ScanResult scan = securityScanner.scan(fullText, documentId);
                    if (securityScannerProperties.shouldBlock(scan)) {
                        String reason = "security_blocked: " + scan.summary();
                        log.warn(
                                "parse.security_blocked doc_id={}, outcome={}, threats={}",
                                documentId,
                                scan.outcome(),
                                scan.threats().size());
                        throw new IllegalStateException(reason);
                    }
                    if (scan.outcome() != ScanResult.Outcome.CLEAN) {
                        log.info(
                                "parse.security_tagged doc_id={}, outcome={}, summary={}",
                                documentId,
                                scan.outcome(),
                                scan.summary());
                    }
                } catch (IllegalStateException blockEx) {
                    // BLOCK 模式: 重抛让外层 catch 走 markFailed
                    throw blockEx;
                } catch (Exception scanEx) {
                    // scanner 自身异常不挂主流程 (e.g. 正则 stack overflow), 仅 log; 续 chunk
                    log.warn(
                            "parse.security_scan_failed doc_id={}, error={} (continue chunk)",
                            documentId,
                            scanEx.getMessage());
                }
            }

            // 4. 切片(P3-A: feature flag flat|parent_child, 默认 flat 兼容老路径)
            boolean useParentChild =
                    chunkingProps.getMode() == ChunkingProperties.Mode.PARENT_CHILD;

            List<Chunk> savedChunks;

            if (useParentChild) {
                savedChunks = parseParentChild(doc, documentId, fullText, redactionCount);
            } else {
                savedChunks = parseFlat(doc, documentId, fullText, redactionCount);
            }

            // 9. 状态机迁移终端: PARSING → ... → INDEXED
            //    中间态 (CHUNKED/EMBEDDING/INDEXING) 已由 parseFlat/parseParentChild 内部
            //    通过 DocumentStatePort 推进 — 仅最后一步 markIndexed 在此持久化。
            doc.markIndexed();
            statePort.markIndexed(documentId);
            log.info(
                    "parse.done doc_id={}, status={}, chunks={}, mode={}",
                    documentId,
                    doc.status(),
                    savedChunks.size(),
                    useParentChild ? "parent_child" : "flat");

        } catch (Exception e) {
            log.error("parse.failed doc_id={}", documentId, e);
            String reason = truncate(e.getMessage(), 500);
            try {
                // FAILED 是终止态之一, 但若 startParsing 之前就抛, 这是合理的; 若已经 PARSING 也可 markFailed
                doc.markFailed(reason);
                documentRepository.save(doc);
            } catch (Exception markFailure) {
                // markFailed 自身失败(如已经是 FAILED) 决不掩盖原始错误
                log.warn(
                        "parse.mark_failed_failed doc_id={}, err={}",
                        documentId,
                        markFailure.getMessage());
            }
            // 重抛让上游(DocumentUploadService)决策: 当前事务是否回滚
            throw new IllegalStateException("解析失败 doc_id=" + documentId + ", reason=" + reason, e);
        }
    }

    /** 与 {@code MinioFileStorage.uploadRaw} 的 key 规则对齐: raw/{docId}/{filename}。 */
    private static String buildObjectKey(Document doc) {
        return "raw/" + doc.id().value() + "/" + sanitize(doc.originalFilename());
    }

    /** flat 模式: 单层 token-based 切片 + embed + 入 Milvus + markReady(返回 savedChunks)。 */
    private List<Chunk> parseFlat(
            Document doc, Long documentId, String fullText, int redactionCount) {
        List<ChunkingService.SectionedFlatChunk> sectioned =
                chunkingService.chunkSectioned(fullText);
        if (sectioned.isEmpty()) {
            throw new IllegalStateException("切片结果为空(全文过短或全是噪声)");
        }
        List<String> chunkTexts =
                sectioned.stream().map(ChunkingService.SectionedFlatChunk::text).toList();
        log.info("parse.chunked doc_id={}, chunks={}", documentId, chunkTexts.size());

        List<Chunk> chunks = new ArrayList<>(sectioned.size());
        for (int i = 0; i < sectioned.size(); i++) {
            String text = sectioned.get(i).text();
            chunks.add(
                    new Chunk(
                            null,
                            documentId,
                            i,
                            ChunkType.TEXT,
                            text,
                            0,
                            null,
                            null,
                            sha256Hex(text),
                            sectioned.get(i).sectionPath()));
        }
        if (ingestionPolicy != null) {
            chunks = ingestionPolicy.deduplicateChunks(documentId, chunks, redactionCount);
        }
        chunkTexts = contextualEmbedInputs(doc, chunks);
        // Task 4: 顺序 — CHUNKED 在切片完成时; EMBEDDING 在调 embedding 前;
        //          INDEXING 在 upsert 前; INDEXED 在外层 trigger 完成。
        // flat 路径: chunks 已构造 (in-memory), 先 mark CHUNKED
        doc.markChunked(chunks);
        statePort.markChunked(documentId, chunks);

        // EMBEDDING: 调 embedding API 前再迁
        doc.markEmbedding();
        statePort.markEmbedding(documentId);
        List<EmbeddingResult> embeddings = embeddingClient.embedBatch(chunkTexts);
        if (ingestionPolicy != null) {
            ingestionPolicy.validateEmbeddings(
                    documentId, embeddings, chunks.size(), redactionCount);
        } else if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("embed 数量与 chunks 不一致");
        }
        List<Chunk> savedChunks = chunkRepository.saveAll(documentId, chunks);
        vectorStore.deleteByDocumentId(documentId);
        VectorStore.ChunkMetadata md =
                new VectorStore.ChunkMetadata(
                        doc.source(),
                        doc.version(),
                        doc.language(),
                        doc.docType(),
                        ChunkType.TEXT.name(),
                        doc.tenantId(),
                        doc.logicalDocumentKey());
        // INDEXING: Milvus upsert 前再迁
        doc.markIndexing();
        statePort.markIndexing(documentId);
        vectorStore.upsertChunks(documentId, savedChunks, embeddings, md);
        return savedChunks;
    }

    /**
     * parent-child 模式: 两层级切片 + embed child + 入 Milvus + 返回 (parents ∪ children)。
     *
     * <p>关键设计(参考 LlamaIndex HierarchicalNodeParser):
     *
     * <ul>
     *   <li>parent 全文存 MySQL 但<b>不入 Milvus</b>(用于 context 回链)
     *   <li>child 入 Milvus 索引(检索准)
     *   <li>child.parentChunkId 关联到真实 parent chunk id
     * </ul>
     */
    private List<Chunk> parseParentChild(
            Document doc, Long documentId, String fullText, int redactionCount) {
        List<ChunkingService.SectionedParentChildChunk> pcChunks =
                chunkingService.chunkParentChildSectioned(fullText);
        if (pcChunks.isEmpty()) {
            throw new IllegalStateException("Parent-Child 切片结果为空");
        }

        // 按 parent 文本去重 → 唯一 parents(保证同段 parent 只存一份)
        java.util.Map<String, Integer> parentTextToId = new java.util.LinkedHashMap<>();
        java.util.List<String> uniqueParentTexts = new java.util.ArrayList<>();
        for (var pc : pcChunks) {
            if (!parentTextToId.containsKey(pc.parentText())) {
                parentTextToId.put(pc.parentText(), uniqueParentTexts.size());
                uniqueParentTexts.add(pc.parentText());
            }
        }

        log.info(
                "parse.parent_child_chunked doc_id={}, children={}, unique_parents={}",
                documentId,
                pcChunks.size(),
                uniqueParentTexts.size());

        // 步骤 A: 先存 parent chunks 拿真实 id(不入 Milvus)
        java.util.List<Chunk> parentChunks = new java.util.ArrayList<>();
        for (int i = 0; i < uniqueParentTexts.size(); i++) {
            String ptext = uniqueParentTexts.get(i);
            parentChunks.add(
                    new Chunk(
                            null,
                            documentId,
                            i,
                            ChunkType.PARENT,
                            ptext,
                            0,
                            null,
                            null,
                            sha256Hex(ptext),
                            // parent 的 sectionPath 取它第一个 child 的 sectionPath
                            pcChunks.stream()
                                    .filter(c -> c.parentText().equals(ptext))
                                    .map(ChunkingService.SectionedParentChildChunk::sectionPath)
                                    .findFirst()
                                    .orElse(List.of())));
        }
        // 同文档重新解析时先清旧(parents+children): deleteByDocumentId 通过 saveAll 内部完成
        java.util.List<Chunk> savedParents = chunkRepository.saveAll(documentId, parentChunks);
        // Task 4: PARSING → CHUNKED (parents 落库; children 后续 append, 状态机这里就推进,
        // 真实 chunks 列表给 reconcile 看 no-op 即可, markChunked 主要为状态机推进)
        doc.markChunked(savedParents);
        statePort.markChunked(documentId, savedParents);
        java.util.Map<String, Long> parentTextToRealId = new java.util.HashMap<>();
        for (Chunk sp : savedParents) {
            parentTextToRealId.put(sp.content(), sp.id());
        }

        // 步骤 B: 构造 children, parent_chunk_id 关联到 savedParents 真实 id
        java.util.List<String> childTexts = new java.util.ArrayList<>();
        java.util.List<Chunk> childChunks = new java.util.ArrayList<>();
        int childSeq = 0;
        for (var pc : pcChunks) {
            Long pid = parentTextToRealId.get(pc.parentText());
            childChunks.add(
                    new Chunk(
                            null,
                            documentId,
                            childSeq++,
                            ChunkType.CHILD,
                            pc.childText(),
                            0,
                            null,
                            pid,
                            sha256Hex(pc.childText()),
                            pc.sectionPath()));
            childTexts.add(pc.childText());
        }

        if (ingestionPolicy != null) {
            childChunks = new java.util.ArrayList<>(
                    ingestionPolicy.deduplicateChunks(documentId, childChunks, redactionCount));
        }
        childTexts = new java.util.ArrayList<>(contextualEmbedInputs(doc, childChunks));

        // 步骤 C: embed 只 children
        // Task 4: CHUNKED → EMBEDDING
        doc.markEmbedding();
        statePort.markEmbedding(documentId);
        List<EmbeddingResult> embeddings = embeddingClient.embedBatch(childTexts);
        if (ingestionPolicy != null) {
            ingestionPolicy.validateEmbeddings(
                    documentId, embeddings, childChunks.size(), redactionCount);
        } else if (embeddings.size() != childChunks.size()) {
            throw new IllegalStateException("embed 数量与 child chunks 不一致");
        }

        // 步骤 D: 追加 children(不清旧, parents 已在步骤 A 落库)
        java.util.List<Chunk> savedChildren =
                chunkRepository.saveAllAppend(documentId, childChunks);

        // 步骤 E: vectorStore 只写 children
        vectorStore.deleteByDocumentId(documentId);
        VectorStore.ChunkMetadata md =
                new VectorStore.ChunkMetadata(
                        doc.source(),
                        doc.version(),
                        doc.language(),
                        doc.docType(),
                        ChunkType.CHILD.name(),
                        doc.tenantId(),
                        doc.logicalDocumentKey());
        // Task 4: EMBEDDING → INDEXING
        doc.markIndexing();
        statePort.markIndexing(documentId);
        vectorStore.upsertChunks(documentId, savedChildren, embeddings, md);
        log.info(
                "parse.parent_child_indexed doc_id={}, parents={}, children_in_milvus={}",
                documentId,
                savedParents.size(),
                savedChildren.size());

        java.util.List<Chunk> allSaved = new java.util.ArrayList<>(savedParents);
        allSaved.addAll(savedChildren);
        return allSaved;
    }

    private String extractText(Document doc, byte[] bytes) throws Exception {
        // Tika 通过 mime 自动路由到 PDFParser / HtmlParser / 等等
        // Tika.parseToString 会按 metadata 决定解码; 给出 mimeType 提示让 AutoDetect 更准
        org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
        if (doc.mimeType() != null) {
            metadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, doc.mimeType());
        }
        try (var input = new java.io.ByteArrayInputStream(bytes)) {
            return tika.parseToString(input, metadata);
        }
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        return filename.replaceAll("[\\\\/]+", "_");
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
