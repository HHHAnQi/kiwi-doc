package com.xxx.ragdoc.infrastructure.parse;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.chunking.ChunkingService;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.FileStorage;
import com.xxx.ragdoc.application.document.port.VectorStore;
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
 *   <li>doc.markReady(chunks) 状态机迁移到 READY
 * </ol>
 *
 * <p>异常处理: 任一步失败 → markFailed 并落库; 传播异常给上游 DocumentUploadService 决策(是否回滚/重试)。 资源安全(Scanner/流)不在此管:
 * Tika 内部已处理; 字节缓冲一次性读入。<br>
 * V3 演进: 替换为发 DocumentParsedRequest 到 MQ, 由独立 parser-service 异步消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TikaParsingTrigger implements ParsingTrigger {

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;
    private final VectorStore vectorStore;

    /** Tika 是线程安全(内部 facade 单例), 复用即可。 */
    private final Tika tika = new Tika();

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

            // 4. 切片
            List<String> chunkTexts = chunkingService.chunk(fullText);
            if (chunkTexts.isEmpty()) {
                throw new IllegalStateException("切片结果为空(全文过短或全是噪声)");
            }
            log.info("parse.chunked doc_id={}, chunks={}", documentId, chunkTexts.size());

            // 5. 构建 domain.Chunk(V2 简化: page=0 全文块, type=TEXT, bbox/parent 留空)
            List<Chunk> chunks = new ArrayList<>(chunkTexts.size());
            for (int i = 0; i < chunkTexts.size(); i++) {
                String text = chunkTexts.get(i);
                chunks.add(
                        new Chunk(
                                null, // id 由 DB 自增
                                documentId,
                                i, // seq 从 0 起
                                ChunkType.TEXT,
                                text,
                                0, // V2 不做页码检测
                                null,
                                null,
                                sha256Hex(text)));
            }

            // 6. 批量 embed(与 chunks 同序)
            List<EmbeddingResult> embeddings = embeddingClient.embedBatch(chunkTexts);
            if (embeddings.size() != chunks.size()) {
                throw new IllegalStateException(
                        "embed 数量与 chunks 不一致: chunks="
                                + chunks.size()
                                + ", embeddings="
                                + embeddings.size());
            }

            // 7. 落 MySQL(自带清旧, 幂等)
            List<Chunk> savedChunks = chunkRepository.saveAll(documentId, chunks);

            // 8. 写 Milvus(重新解析时显式清旧向量, saveAll 已清 chunk 表, 但 vector 需独立清)
            vectorStore.deleteByDocumentId(documentId);
            vectorStore.upsertChunks(documentId, savedChunks, embeddings);

            // 9. 状态机迁移: PARSING → READY
            doc.markReady(savedChunks);
            documentRepository.save(doc);
            log.info(
                    "parse.done doc_id={}, status={}, chunks={}",
                    documentId,
                    doc.status(),
                    savedChunks.size());

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
