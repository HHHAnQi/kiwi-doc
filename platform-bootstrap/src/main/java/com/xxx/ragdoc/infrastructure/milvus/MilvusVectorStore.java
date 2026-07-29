package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link VectorStore} 的 Milvus 实现。
 *
 * <p>V2-A: upsertChunks + deleteByDocumentId 真正落地; search 方法 V2-B RetrieveService 接入后实现, V2-A
 * 先返回空(避免编译报错但不被调用)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorStore implements VectorStore {

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties props;

    @Override
    public void upsertChunks(
            Long documentId, List<Chunk> chunks, List<EmbeddingResult> embeddings) {
        if (chunks.isEmpty() || embeddings.isEmpty()) {
            return;
        }
        // 先删旧(重新解析幂等)
        deleteByDocumentId(documentId);

        // 构造 Milvus insert 行
        List<InsertParam.Field> fields = new ArrayList<>();
        // id auto-id, 不插入

        // dense_vector field: 每行 float[]
        List<List<Float>> denseRows =
                embeddings.stream()
                        .map(e -> toFloatList(e.denseVector()))
                        .collect(Collectors.toList());
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_DENSE, denseRows));

        // sparse_vector field: Milvus 严格校验 SortedMap<Long, Float>
        // (SDK 抛 "SparseFloatVector vector field's value type must be SortedMap<Long, Float>")
        List<SortedMap<Long, Float>> sparseRows =
                embeddings.stream().map(this::toSparseMap).collect(Collectors.toList());
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_SPARSE, sparseRows));

        // document_id field
        List<Long> docIdRows = chunks.stream().map(c -> documentId).collect(Collectors.toList());
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_DOC_ID, docIdRows));

        // chunk_id field (回溯 MySQL)
        List<Long> chunkIdRows = chunks.stream().map(c -> c.id()).collect(Collectors.toList());
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_CHUNK_ID, chunkIdRows));

        // page field
        List<Integer> pageRows = chunks.stream().map(Chunk::page).collect(Collectors.toList());
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_PAGE, pageRows));

        // tenant_id field
        List<String> tenantRows = Collections.nCopies(chunks.size(), "default");
        fields.add(new InsertParam.Field(MilvusCollectionInitializer.FIELD_TENANT, tenantRows));

        ensureCollectionLoaded();
        InsertParam insertParam =
                InsertParam.newBuilder()
                        .withCollectionName(props.getCollection())
                        .withFields(fields)
                        .build();
        R<io.milvus.grpc.MutationResult> resp = milvusClient.insert(insertParam);
        if (resp.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus insert 失败: " + resp.getMessage());
        }
        log.info(
                "milvus.upsert doc_id={}, chunks={}, embeds={}",
                documentId,
                chunks.size(),
                embeddings.size());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        ensureCollectionLoaded();
        String expr = "document_id == " + documentId;
        R<io.milvus.grpc.MutationResult> resp =
                milvusClient.delete(
                        DeleteParam.newBuilder()
                                .withCollectionName(props.getCollection())
                                .withExpr(expr)
                                .build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            log.warn("milvus.delete_failed doc_id={}, msg={}", documentId, resp.getMessage());
        }
    }

    /**
     * V2-B: dense HNSW 检索。
     *
     * <p>设计取舍: V2-A 阶段 sparse 字段是占位 token(0L→0.0), sparse 通道无信号。 当前先走 dense-only ANN(IP + HNSW),
     * 已足够 SCA 文档问答。 V2-C 切到 hfei /embed + /sparse 双调用后, sparse 会有真值, 那时再升级为 hybrid search + RRF 融合。
     *
     * <p>过滤逻辑: docId==null 时跨全库检索, 否则按 document_id 过滤(Milvus expr)。 返回 chunk_id 列表(按分数降序), 由
     * RetrieveService 回查 MySQL 拼 Citation。
     */
    @Override
    public List<Long> search(EmbeddingResult queryEmbedding, Long docId, int topK) {
        ensureCollectionLoaded();

        // 构造 docId 过滤表达式(Milvus expr 语法, null = 跨全库)
        String expr = (docId == null) ? null : "document_id == " + docId;

        SearchParam.Builder searchBuilder =
                SearchParam.newBuilder()
                        .withCollectionName(props.getCollection())
                        .withVectorFieldName(MilvusCollectionInitializer.FIELD_DENSE)
                        .withFloatVectors(List.of(toFloatList(queryEmbedding.denseVector())))
                        .withTopK(topK)
                        .withMetricType(MetricType.IP)
                        // HNSW 搜索 ef>=topK*4 才能保证召回率, 否则近似质量差
                        // SDK 2.4 SearchParam 用 withParams(String) 装额外参数
                        .withParams("{\"params\":{\"ef\":" + Math.max(64, topK * 4) + "}}")
                        .withOutFields(List.of(MilvusCollectionInitializer.FIELD_CHUNK_ID));
        if (expr != null) {
            searchBuilder.withExpr(expr);
        }

        R<io.milvus.grpc.SearchResults> resp = milvusClient.search(searchBuilder.build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            log.error(
                    "milvus.search_failed docId={}, topK={}, err={}",
                    docId,
                    topK,
                    resp.getMessage());
            return List.of();
        }

        // SearchResultsWrapper.getFieldData("chunk_id", 0) 返回第 0 个 query 的 hits
        // 在该字段列上的值(按分数降序), 直接是 List<Number>
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        Object fieldRaw = wrapper.getFieldData(MilvusCollectionInitializer.FIELD_CHUNK_ID, 0);
        List<Long> chunkIds = new ArrayList<>();
        if (fieldRaw instanceof List<?> rawList) {
            for (Object v : rawList) {
                if (v instanceof Number n) {
                    chunkIds.add(n.longValue());
                }
            }
        }

        log.info("milvus.search done docId={}, topK={}, hits={}", docId, topK, chunkIds.size());
        return chunkIds;
    }

    // ===== 辅助 =====

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private SortedMap<Long, Float> toSparseMap(EmbeddingResult embedding) {
        // Milvus SDK 严格校验: SparseFloatVector 必须 SortedMap<Long, Float>,
        // key 是 Long 类型, 即使序列化为字符串也不接受 (会抛 type mismatch)。
        SortedMap<Long, Float> map = new TreeMap<>();
        for (Map.Entry<Integer, Float> entry : embedding.sparseVector().entrySet()) {
            map.put(entry.getKey().longValue(), entry.getValue());
        }
        // Milvus SDK 拒绝空 SortedMap(ParamException: Not allow empty SortedMap),
        // V2-A 阶段 hfei 返回的是 OpenAI 口径仅含 dense, sparse 为空 Map,
        // 这里写一个占位 token(value=0, 不影响检索打分), 让 insert 通过;
        // V2-B 切到 hfei /embed + /sparse 双调用后, sparse 就会有真实值。
        if (map.isEmpty()) {
            map.put(0L, 0.0f);
        }
        return map;
    }

    private void ensureCollectionLoaded() {
        try {
            milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(props.getCollection())
                            .build());
        } catch (Exception e) {
            log.warn("milvus.load_collection_failed(可能已loaded): {}", e.getMessage());
        }
    }
}
