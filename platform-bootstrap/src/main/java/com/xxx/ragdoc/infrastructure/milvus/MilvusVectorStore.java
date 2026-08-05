package com.xxx.ragdoc.infrastructure.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link VectorStore} 的 Milvus v2 实现。
 *
 * <p>V2-C: 双路召回 (dense + BM25 sparse) + RRF 融合。
 *
 * <p>关键点:
 *
 * <ul>
 *   <li>upsert: dense 向量 + chunk 文本(text 字段)。sparse_bm25 由 Milvus Function 自动算。
 *   <li>search: 走 {@code MilvusClientV2.hybridSearch}, 两路 AnnSearchReq 分别用 dense_vector (FloatVec)
 *       与 sparse_bm25 (EmbeddedText); RRFRanker(k=60) 融合。
 *   <li>docId 过滤: 两路 AnnSearchReq 都带 {@code document_id == X} 过滤表达式。
 * </ul>
 */
@Slf4j
@Component
public class MilvusVectorStore implements VectorStore {

    private final MilvusClientV2 milvusClientV2;
    private final MilvusProperties props;
    private final RetrieveProperties retrieveProps;
    // Phase 3.A: Milvus 调用走 CircuitBreaker(命名 instance "milvus"); 熔断后 InsertService / RetrieveService 抛异常,
    // 由各自 flow 处理(retrieve 走 empty; parse queue 重试或 DLQ)。
    private final CircuitBreaker circuitBreaker;
    private final Gson gson = new Gson();

    public MilvusVectorStore(
            MilvusClientV2 milvusClientV2,
            MilvusProperties props,
            RetrieveProperties retrieveProps,
            CircuitBreakerRegistry cbRegistry) {
        this.milvusClientV2 = milvusClientV2;
        this.props = props;
        this.retrieveProps = retrieveProps;
        this.circuitBreaker = cbRegistry.circuitBreaker("milvus");
    }

    @Override
    public void upsertChunks(
            Long documentId,
            List<Chunk> chunks,
            List<EmbeddingResult> embeddings,
            VectorStore.ChunkMetadata metadata) {
        if (chunks.isEmpty() || embeddings.isEmpty()) {
            return;
        }
        deleteByDocumentId(documentId);

        VectorStore.ChunkMetadata md =
                metadata == null ? VectorStore.ChunkMetadata.unknown() : metadata;

        // 构造行: InsertReq.data 是 List<JsonObject> (gson)。
        // dense_vector 字段 → JsonArray; 其他标量原样。
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            EmbeddingResult e = embeddings.get(i);

            JsonObject row = new JsonObject();
            row.add(MilvusCollectionInitializer.FIELD_DENSE, toJsonArray(e.denseVector()));
            // text 字段截 TEXT_MAX_LENGTH, 防 VarChar 越界。Function 据此自动算 sparse_bm25。
            // Q3-A 修复: VARCHAR maxLength 在 Milvus 是 byte 长度(UTF-8 编码后), 不是 char 数。
            // 中文 1 char = 3 bytes → 4000 bytes 容纳 ~1333 个中文字符。原来的 substring 按字符截
            // 中文文本超 4000 bytes 仍报 "length exceeds max length"。改用 UTF-8 byte-aware truncation。
            String text = c.content() == null ? "" : c.content();
            text = truncateUtf8Bytes(text, MilvusCollectionInitializer.TEXT_MAX_LENGTH);
            row.addProperty(MilvusCollectionInitializer.FIELD_TEXT, text);
            row.addProperty(MilvusCollectionInitializer.FIELD_DOC_ID, documentId);
            row.addProperty(MilvusCollectionInitializer.FIELD_CHUNK_ID, c.id());
            row.addProperty(MilvusCollectionInitializer.FIELD_PAGE, c.page());
            row.addProperty(MilvusCollectionInitializer.FIELD_TENANT,
                    (md.tenantId() == null || md.tenantId().isBlank()) ? "default" : md.tenantId());
            // V3 业务元数据标量: 支持 Milvus expr 过滤检索(如 source=='nacos' && version=='2.4')
            row.addProperty(MilvusCollectionInitializer.FIELD_SOURCE, md.source());
            // version 字段 Milvus SDK 2.5 不支持 nullable, null 时存空串占位。
            row.addProperty(
                    MilvusCollectionInitializer.FIELD_VERSION,
                    md.version() == null ? "" : md.version());
            row.addProperty(MilvusCollectionInitializer.FIELD_LANGUAGE, md.language());
            row.addProperty(MilvusCollectionInitializer.FIELD_DOC_TYPE, md.docType());
            // chunkType: chunk 自身的 type 优先于 metadata.chunkType(后者是文档级缺省)
            String chunkType =
                    (c.type() != null && !c.type().name().isBlank())
                            ? c.type().name()
                            : md.chunkType();
            row.addProperty(MilvusCollectionInitializer.FIELD_CHUNK_TYPE, chunkType);
            rows.add(row);
        }

        // Phase 3.A: insert 调用走 CB。熔断态抛 CallNotPermittedException 上抛 InsertService,
        // parse 队列据此重投或 DLQ。
        circuitBreaker.executeRunnable(
                () ->
                        milvusClientV2.insert(
                                InsertReq.builder()
                                        .collectionName(props.getCollection())
                                        .data(rows)
                                        .build()));
        log.info(
                "milvus.upsert doc_id={}, chunks={}, source={}, chunks_type={}",
                documentId,
                chunks.size(),
                md.source(),
                chunks.get(0).type() == null ? md.chunkType() : chunks.get(0).type().name());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        // Phase 3.A: delete 调用走 CB。熔断态抛 CallNotPermittedException 上抛, 让 DeleteService 决策重试 / 标记 pending。
        circuitBreaker.executeRunnable(
                () ->
                        milvusClientV2.delete(
                                DeleteReq.builder()
                                        .collectionName(props.getCollection())
                                        .filter("document_id == " + documentId)
                                        .build()));
    }

    @Override
    public List<ScoredChunk> search(
            EmbeddingResult queryEmbedding,
            String queryText,
            Long docId,
            int topK,
            VectorStore.MetadataFilter filter) {
        String expr = MilvusFilterExprBuilder.build(docId, filter);
        // feature flag: dense-only 时只跑单路 dense ANN, 绕过 BM25 sparse RRFRanker
        // (BM25 在小数据集 + 含噪音 chunk 下劣化, 已被 CI 门禁 block 两次)
        if (retrieveProps.getMode() == RetrieveProperties.Mode.DENSE) {
            return searchDense(queryEmbedding, expr, topK);
        }
        return searchHybrid(queryEmbedding, queryText, expr, topK);
    }

    /** 单路 dense 检索(当前生产基线)。 */
    private List<ScoredChunk> searchDense(EmbeddingResult queryEmbedding, String expr, int topK) {
        var reqBuilder =
                io.milvus.v2.service.vector.request.SearchReq.builder()
                        .collectionName(props.getCollection())
                        .data(java.util.List.of(new FloatVec(queryEmbedding.denseVector())))
                        .annsField(MilvusCollectionInitializer.FIELD_DENSE)
                        .topK(topK)
                        .outputFields(
                                java.util.List.of(MilvusCollectionInitializer.FIELD_CHUNK_ID));
        if (expr != null) {
            reqBuilder.filter(expr);
        }
        // Phase 3.A: dense search 调用走 CB
        SearchResp resp =
                circuitBreaker.executeSupplier(() -> milvusClientV2.search(reqBuilder.build()));
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results.isEmpty()) return List.of();

        List<ScoredChunk> scored = new ArrayList<>();
        for (SearchResp.SearchResult hit : results.get(0)) {
            Object cidRaw = hit.getEntity().get(MilvusCollectionInitializer.FIELD_CHUNK_ID);
            if (cidRaw instanceof Number n) {
                scored.add(new ScoredChunk(n.longValue(), hit.getScore().floatValue()));
            }
        }
        log.info("milvus.dense_search topK={}, expr={}, hits={}", topK, expr, scored.size());
        return scored;
    }

    /**
     * 双路 hybridSearch: dense(BGE-M3) + sparse(BM25) → RRF(k=60) → top-k。
     *
     * <p>为什么用 RRF: BGE dense 分数(IP余弦, 0~1) 与 BM25 分数(任意正向无界) 尺度不同, 不能直接 加权相加。RRF 只用 rank(位次),
     * 无尺度问题, 是 Milvus 默认推荐融合算法(k=60)。
     */
    private List<ScoredChunk> searchHybrid(
            EmbeddingResult queryEmbedding, String queryText, String expr, int topK) {
        // dense 路: BGE-M3 dense 向量(FloatVec 直接吃 float[])
        BaseVector denseVec = new FloatVec(queryEmbedding.denseVector());
        AnnSearchReq.AnnSearchReqBuilder<?, ?> denseBuilder =
                AnnSearchReq.builder()
                        .vectorFieldName(MilvusCollectionInitializer.FIELD_DENSE)
                        .vectors(List.of(denseVec))
                        .topK(topK * 4) // 召回宽于 topK 给 RRF 留排序空间
                        .metricType(IndexParam.MetricType.IP);
        if (expr != null) {
            denseBuilder.filter(expr);
        }
        AnnSearchReq denseReq = denseBuilder.build();

        // sparse 路: BM25, query 是原文本(EmbeddedText 类型, Milvus 内部分词索引)
        BaseVector sparseVec = new EmbeddedText(queryText);
        AnnSearchReq.AnnSearchReqBuilder<?, ?> sparseBuilder =
                AnnSearchReq.builder()
                        .vectorFieldName(MilvusCollectionInitializer.FIELD_SPARSE_BM25)
                        .vectors(List.of(sparseVec))
                        .topK(topK * 4)
                        .metricType(IndexParam.MetricType.BM25);
        if (expr != null) {
            sparseBuilder.filter(expr);
        }
        AnnSearchReq sparseReq = sparseBuilder.build();

        HybridSearchReq hybridReq =
                HybridSearchReq.builder()
                        .collectionName(props.getCollection())
                        .searchRequests(List.of(denseReq, sparseReq))
                        .ranker(new RRFRanker(60)) // k=60, 与文档 chat/spec.md L65 公式一致
                        .topK(topK)
                        .outFields(List.of(MilvusCollectionInitializer.FIELD_CHUNK_ID))
                        .build();

        // Phase 3.A: hybrid search 调用走 CB
        SearchResp resp = circuitBreaker.executeSupplier(() -> milvusClientV2.hybridSearch(hybridReq));
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (SearchResp.SearchResult hit : results.get(0)) {
            Object cidRaw = hit.getEntity().get(MilvusCollectionInitializer.FIELD_CHUNK_ID);
            if (cidRaw instanceof Number n) {
                scored.add(new ScoredChunk(n.longValue(), hit.getScore().floatValue()));
            }
        }
        log.info("milvus.hybrid_search topK={}, expr={}, hits={}", topK, expr, scored.size());
        return scored;
    }

    private static JsonArray toJsonArray(float[] arr) {
        JsonArray out = new JsonArray(arr.length);
        for (float v : arr) {
            out.add(v);
        }
        return out;
    }

    /**
     * 按 UTF-8 字节长度截断字符串, 不在多字节字符中间断。Milvus VARCHAR maxLength 是 byte 长度。
     *
     * <p>Q3-A 修复: 之前用 {@code substring(0, maxChars)} 按字符截, 中文文本 >4000 bytes 仍报 "length exceeds max
     * length"(因为原串 byte count 实际超VARCHAR 上限)。改 UTF-8 byte-aware 截断后, 截到 maxBytes 之内的最后一个完整字符位置。
     */
    private static String truncateUtf8Bytes(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return "";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return s;
        // ByteBuffer decode 不在多字节中段截; 但 bytes[maxBytes] 起始字节可能落在某多字节中段。
        // 用 CharsetDecoder 报错回退方式: 找一个不超 maxBytes 的最大 p, 满足 bytes[p-1] 不是
        // continue-byte (即 0x80..0xBF)
        int p = maxBytes;
        // continue-byte check: 退到上一字符边界(顺 0x80..0xBF 后退)
        while (p > 0 && (bytes[p] & 0xC0) == 0x80) p--;
        return new String(bytes, 0, p, java.nio.charset.StandardCharsets.UTF_8);
    }
}
