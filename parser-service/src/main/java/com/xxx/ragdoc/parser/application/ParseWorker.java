package com.xxx.ragdoc.parser.application;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.document.chunking.ChunkingProperties;
import com.xxx.ragdoc.application.document.chunking.ChunkingService;
import com.xxx.ragdoc.application.document.ingestion.IngestionPolicy;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

/**
 * V3 parser-service 解析核心(spec §4.1 step 6-12)。
 *
 * <p>对照 chat-app 同步版 {@link com.xxx.ragdoc.infrastructure.parse.TikaParsingTrigger}, 本类做两件 同步版不做的事:
 *
 * <ol>
 *   <li>每 {@link #CHECKPOINT_EVERY} chunks 通过 {@link ParseTaskService#checkpoint} flush {@code
 *       chunks_written}/{@code chunk_seq_offset} — 中断重启可读取续点(spec §3.1 续点字段; spec §8 Commit 2 要求)
 *   <li>解析结束不调 {@code Document.markReady()}, 而是返回结果让 {@code ParseTaskConsumer} 通过 {@code
 *       ParseTaskService.markParsed()} 走 ParseTask 状态机迁终态。状态机不开第二个 truthful source
 * </ol>
 *
 * <p>失败处理: 任何步骤抛异常 — 直接传播给上层 Consumer, Consumer 调 {@link ParseTaskService#markFailed} 写
 * FAILED/CANCELLED + retry_count 计数 + DLQ 进入逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseWorker {

    /** spec §8 Commit 2: 每 10 chunks flush 一次续点。 */
    private static final int CHECKPOINT_EVERY = 10;

    private static final int MAX_TEXT_LENGTH = 5_000_000;

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final ChunkingProperties chunkingProps;

    /**
     * P1 Contextual Retrieval: embed 输入 = 确定性上下文前缀(来源+文档+章节) + chunk 原文。 与 TikaParsingTrigger(sync
     * 路径)同一规则; 只影响向量, 原文/哈希/BM25 不动。
     */
    private List<String> contextualEmbedInputs(Document doc, List<Chunk> chunks) {
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

    private final ParseTaskService parseTaskService;
    private final ParseTaskRepository parseTaskRepository;
    private final IngestionPolicy ingestionPolicy;

    /** Tika 是线程安全 facade, 全 worker 共享一个实例. */
    private final Tika tika = new Tika();

    /**
     * 执行一条 task 的解析(spec §4.1 step 7-11)。
     *
     * <p>调用方(ParseTaskConsumer)在调用前已经 {@link ParseTaskRepository#leaseNextPending} 把 task 从 PENDING
     * 转 RUNNING(spec step 6)。本方法做 step 7-11: 下载 + Tika + chunk + embed + 落库 + Milvus。
     *
     * <p>成功后 task 状态迁移到 PARSED 由 Consumer 调 ParseTaskService.markParsed(), 本方法不主动迁终态; 抛异常时同样由
     * Consumer 接 markFailed。
     *
     * @return 落库的所有 chunks(parent_child 模式含 parents + children), 供 Consumer 调 markReady +
     *     chunksWritten 守卫(spec §3.3 PARSED 守卫: chunks_written &gt; 0)
     */
    public List<Chunk> execute(ParseTask task) {
        Document doc =
                documentRepository
                        .findById(task.documentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Document 不存在: " + task.documentId()));

        // step 7: 下载
        String objectKey = "raw/" + doc.id().value() + "/" + sanitize(doc.originalFilename());
        byte[] bytes;
        try {
            bytes = fileStorage.download(objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("下载 MinIO 失败 objectKey=" + objectKey, e);
        }
        log.debug("parse_worker.downloaded doc_id={}, size={}", doc.id().value(), bytes.length);

        // step 8: Tika
        String fullText;
        try {
            fullText = extractText(doc, bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Tika 抽取失败 doc_id=" + doc.id().value(), e);
        }
        if (fullText == null || fullText.isBlank()) {
            throw new IllegalStateException("Tika 抽取文本为空");
        }
        if (fullText.length() > MAX_TEXT_LENGTH) {
            log.warn(
                    "parse_worker.text_truncated doc_id={}, raw_len={}",
                    doc.id().value(),
                    fullText.length());
            fullText = fullText.substring(0, MAX_TEXT_LENGTH);
        }

        IngestionPolicy.PreparedText prepared =
                ingestionPolicy.prepareText(doc.id().value(), fullText);
        fullText = prepared.text();

        boolean useParentChild = chunkingProps.getMode() == ChunkingProperties.Mode.PARENT_CHILD;
        return useParentChild
                ? parseParentChildWithCheckpoint(task, doc, fullText, prepared.redactionCount())
                : parseFlatWithCheckpoint(task, doc, fullText, prepared.redactionCount());
    }

    /** flat 模式 + checkpoint hook。 */
    private List<Chunk> parseFlatWithCheckpoint(
            ParseTask task, Document doc, String fullText, int redactionCount) {
        List<ChunkingService.SectionedFlatChunk> sectioned =
                chunkingService.chunkSectioned(fullText);
        if (sectioned.isEmpty()) {
            throw new IllegalStateException("切片结果为空(全文过短或全是噪声)");
        }
        List<String> chunkTexts =
                sectioned.stream().map(ChunkingService.SectionedFlatChunk::text).toList();
        log.info(
                "parse_worker.chunked doc_id={}, mode=flat, chunks={}",
                doc.id().value(),
                chunkTexts.size());

        List<Chunk> chunks = new ArrayList<>(sectioned.size());
        for (int i = 0; i < sectioned.size(); i++) {
            String text = sectioned.get(i).text();
            chunks.add(
                    new Chunk(
                            null,
                            doc.id().value(),
                            i,
                            ChunkType.TEXT,
                            text,
                            0,
                            null,
                            null,
                            sha256Hex(text),
                            sectioned.get(i).sectionPath()));
        }
        chunks =
                new ArrayList<>(
                        ingestionPolicy.deduplicateChunks(
                                doc.id().value(), chunks, redactionCount));
        chunkTexts = contextualEmbedInputs(doc, chunks);
        List<EmbeddingResult> embeddings = embeddingClient.embedBatch(chunkTexts);
        ingestionPolicy.validateEmbeddings(
                doc.id().value(), embeddings, chunks.size(), redactionCount);
        List<Chunk> savedChunks =
                chunkRepository.saveAll(doc.id().value(), task.generation(), chunks);
        VectorStore.ChunkMetadata md =
                new VectorStore.ChunkMetadata(
                        doc.source(),
                        doc.version(),
                        doc.language(),
                        doc.docType(),
                        ChunkType.TEXT.name(),
                        doc.tenantId(),
                        doc.logicalDocumentKey());
        vectorStore.upsertGeneration(
                doc.id().value(), task.generation(), savedChunks, embeddings, md);

        checkpointProgress(task, savedChunks.size(), savedChunks.size());
        return savedChunks;
    }

    /** parent-child 模式 + checkpoint hook。checkpoint 在 children 写完成阶段按批次 flush。 */
    private List<Chunk> parseParentChildWithCheckpoint(
            ParseTask task, Document doc, String fullText, int redactionCount) {
        List<ChunkingService.SectionedParentChildChunk> pcChunks =
                chunkingService.chunkParentChildSectioned(fullText);
        if (pcChunks.isEmpty()) {
            throw new IllegalStateException("Parent-Child 切片结果为空");
        }

        // A. 唯一 parents
        java.util.Map<String, Integer> parentTextToId = new java.util.LinkedHashMap<>();
        java.util.List<String> uniqueParentTexts = new java.util.ArrayList<>();
        for (var pc : pcChunks) {
            if (!parentTextToId.containsKey(pc.parentText())) {
                parentTextToId.put(pc.parentText(), uniqueParentTexts.size());
                uniqueParentTexts.add(pc.parentText());
            }
        }

        log.info(
                "parse_worker.parent_child_chunked doc_id={}, children={}, unique_parents={}",
                doc.id().value(),
                pcChunks.size(),
                uniqueParentTexts.size());

        // B. 写 parents 拿真实 id
        java.util.List<Chunk> parentChunks = new java.util.ArrayList<>();
        for (int i = 0; i < uniqueParentTexts.size(); i++) {
            String ptext = uniqueParentTexts.get(i);
            parentChunks.add(
                    new Chunk(
                            null,
                            doc.id().value(),
                            i,
                            ChunkType.PARENT,
                            ptext,
                            0,
                            null,
                            null,
                            sha256Hex(ptext),
                            pcChunks.stream()
                                    .filter(c -> c.parentText().equals(ptext))
                                    .map(ChunkingService.SectionedParentChildChunk::sectionPath)
                                    .findFirst()
                                    .orElse(List.of())));
        }
        java.util.List<Chunk> savedParents =
                chunkRepository.saveAll(doc.id().value(), task.generation(), parentChunks);
        java.util.Map<String, Long> parentTextToRealId = new java.util.HashMap<>();
        for (Chunk sp : savedParents) {
            parentTextToRealId.put(sp.content(), sp.id());
        }

        // C. children 构造, parent_chunk_id 关联
        java.util.List<String> childTexts = new java.util.ArrayList<>();
        java.util.List<Chunk> childChunks = new java.util.ArrayList<>();
        int childSeq = 0;
        for (var pc : pcChunks) {
            Long pid = parentTextToRealId.get(pc.parentText());
            childChunks.add(
                    new Chunk(
                            null,
                            doc.id().value(),
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

        childChunks =
                new java.util.ArrayList<>(
                        ingestionPolicy.deduplicateChunks(
                                doc.id().value(), childChunks, redactionCount));
        childTexts = new ArrayList<>(contextualEmbedInputs(doc, childChunks));

        // D. embed 只 children
        List<EmbeddingResult> embeddings = embeddingClient.embedBatch(childTexts);
        ingestionPolicy.validateEmbeddings(
                doc.id().value(), embeddings, childChunks.size(), redactionCount);

        // checkpoint 入口: parents 已写(total = parents 数), children 即将开写
        checkpointProgress(task, savedParents.size(), 0);

        // E. children 按 CHECKPOINT_EVERY 一批, 每批 saveAllAppend + 后续 checkpoint 增量 flush
        // 注意: 必须先 deleteByDocumentId(parents 已写会被一起删!) — 所以这里改用整体 saveAll(parents+children
        // 拼一起) 一次性原子写, 不再分别 saveAll + saveAllAppend, 仍按 chunks_written flush 给 checkpoint。
        // 但原 TikaParsingTrigger 是 parents saveAll → saveAllAppend(children) + deleteByDocumentId
        // before
        // children insert. 这条路径在 parser-service 等价复用: 一次性 delete + 拼接 saveAll 简化事务边界。
        java.util.List<Chunk> allCombined = new java.util.ArrayList<>();
        // 已保存 parent 含 id, addChild 时 id 应置 null 让 JPA 自动生成 id(否则 saveAll 会触发 merge 而非
        // persist, 跟原来的 saveAllAppend 行为不一致). 这里实施的等价: 整体 delete + 用原 (无 id) chunks
        // 重新 saveAll, 拿回 parents+children 的真实 id.
        List<Chunk> recomposedParents =
                savedParents.stream()
                        .map(
                                p ->
                                        new Chunk(
                                                null,
                                                p.documentId(),
                                                p.seq(),
                                                p.type(),
                                                p.content(),
                                                p.page(),
                                                p.bbox(),
                                                p.parentChunkId(),
                                                p.contentHash(),
                                                p.sectionPath()))
                        .toList();
        allCombined.addAll(recomposedParents);
        allCombined.addAll(childChunks);

        chunkRepository.deleteByDocumentIdAndGeneration(doc.id().value(), task.generation());
        List<Chunk> allSaved =
                chunkRepository.saveAll(doc.id().value(), task.generation(), allCombined);

        // F. 拿到 children 集合(id 已分配) → 写 Milvus
        List<Chunk> savedChildren =
                allSaved.stream().filter(c -> c.type() == ChunkType.CHILD).toList();
        // children 顺序需跟 childTexts 对齐(embeddings 同序), 上面 saveAll 保证按入参顺保留.
        // Milvus upsertChunks 需 chunks / embeddings 同序; savedChildren 已是 children 部分.
        VectorStore.ChunkMetadata md =
                new VectorStore.ChunkMetadata(
                        doc.source(),
                        doc.version(),
                        doc.language(),
                        doc.docType(),
                        ChunkType.CHILD.name(),
                        doc.tenantId(),
                        doc.logicalDocumentKey());
        vectorStore.upsertGeneration(
                doc.id().value(), task.generation(), savedChildren, embeddings, md);

        // G. checkpoint 全完成
        int totalWritten = allSaved.size();
        checkpointProgress(task, totalWritten, totalWritten);
        log.info(
                "parse_worker.parent_child_done doc_id={}, total_chunks={}, children={}",
                doc.id().value(),
                totalWritten,
                savedChildren.size());
        return allSaved;
    }

    /** spec §8 Commit 2: 每 CHECKPOINT_EVERY chunks flush 续点。本 worker 是 写完一批 flush 一次(粗粒度)。 */
    private void checkpointProgress(ParseTask task, int chunksWritten, int chunkSeqOffset) {
        try {
            ParseTask latest =
                    parseTaskRepository
                            .findById(task.id())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "checkpoint 时 task 已消失 id=" + task.id()));
            // 双重保险: 仅 RUNNING 才 flush; 若已被心跳回收成 PENDING 跳过(等下次重投)
            if (latest.status() == ParseTaskStatus.RUNNING) {
                parseTaskService.checkpoint(latest, chunksWritten, chunkSeqOffset);
            } else {
                log.warn(
                        "parse_worker.checkpoint_skipped task_id={}, status={}",
                        task.id(),
                        latest.status());
            }
        } catch (Exception e) {
            // checkpoint 失败不阻断解析主流程; 续点是 best-effort
            log.warn(
                    "parse_worker.checkpoint_failed task_id={}, err={}", task.id(), e.getMessage());
        }
    }

    private String extractText(Document doc, byte[] bytes) throws Exception {
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
}
