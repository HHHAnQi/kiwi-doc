package com.xxx.ragdoc.infrastructure.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
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
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class MilvusVectorStore implements VectorStore {

    private final MilvusClientV2 milvusClientV2;
    private final MilvusProperties props;
    private final RetrieveProperties retrieveProps;
    private final Gson gson = new Gson();

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
            String text = c.content() == null ? "" : c.content();
            if (text.length() > MilvusCollectionInitializer.TEXT_MAX_LENGTH) {
                text = text.substring(0, MilvusCollectionInitializer.TEXT_MAX_LENGTH);
            }
            row.addProperty(MilvusCollectionInitializer.FIELD_TEXT, text);
            row.addProperty(MilvusCollectionInitializer.FIELD_DOC_ID, documentId);
            row.addProperty(MilvusCollectionInitializer.FIELD_CHUNK_ID, c.id());
            row.addProperty(MilvusCollectionInitializer.FIELD_PAGE, c.page());
            row.addProperty(MilvusCollectionInitializer.FIELD_TENANT, "default");
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

        milvusClientV2.insert(
                InsertReq.builder().collectionName(props.getCollection()).data(rows).build());
        log.info(
                "milvus.upsert doc_id={}, chunks={}, source={}, chunks_type={}",
                documentId,
                chunks.size(),
                md.source(),
                chunks.get(0).type() == null ? md.chunkType() : chunks.get(0).type().name());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        milvusClientV2.delete(
                DeleteReq.builder()
                        .collectionName(props.getCollection())
                        .filter("document_id == " + documentId)
                        .build());
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
        SearchResp resp = milvusClientV2.search(reqBuilder.build());
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

        SearchResp resp = milvusClientV2.hybridSearch(hybridReq);
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
}
