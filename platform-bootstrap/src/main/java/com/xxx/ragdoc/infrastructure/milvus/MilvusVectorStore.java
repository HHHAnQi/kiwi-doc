package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
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

    /** V2-B 实现真实 hybrid search + RRF; V2-A 先返回空(TikaParsingTrigger 不会调用 search)。 */
    @Override
    public List<Long> search(EmbeddingResult queryEmbedding, Long docId, int topK) {
        log.warn("milvus.search V2-A 未实现, V2-B 接 RetrieveService 时补", new Throwable());
        return List.of();
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
