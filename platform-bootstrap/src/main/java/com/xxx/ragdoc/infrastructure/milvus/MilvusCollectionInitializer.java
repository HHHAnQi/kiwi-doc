package com.xxx.ragdoc.infrastructure.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Milvus collection 初始化器。
 *
 * <p>启动时幂等创建 collection + 索引; 已存在则跳过。
 *
 * <p>V2-A 简化: 用 Milvus SDK 2.4.x 的 gRPC API 直建。 schema:
 *
 * <ul>
 *   <li>id (INT64, PK, auto-id)
 *   <li>dense_vector (FLOAT_VECTOR, dim=1024) — BGE-M3 dense
 *   <li>sparse_vector (SPARSE_FLOAT_VECTOR) — BGE-M3 sparse
 *   <li>document_id (INT64) — 过滤
 *   <li>chunk_id (INT64) — 回溯 MySQL chunks 表
 *   <li>page (INT32) — 引用回溯
 *   <li>tenant_id (VARCHAR 32) — V4 多租户
 * </ul>
 *
 * <p>索引:
 *
 * <ul>
 *   <li>dense: HNSW (M=16, efConstruction=200, IP)
 *   <li>sparse: SPARSE_INVERTED_INDEX (IP)
 * </ul>
 */
@Slf4j
@Component
public class MilvusCollectionInitializer implements ApplicationRunner {

    public static final int DENSE_DIM = 1024;
    public static final String FIELD_ID = "id";
    public static final String FIELD_DENSE = "dense_vector";
    public static final String FIELD_SPARSE = "sparse_vector";
    public static final String FIELD_DOC_ID = "document_id";
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_PAGE = "page";
    public static final String FIELD_TENANT = "tenant_id";

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties props;

    public MilvusCollectionInitializer(MilvusServiceClient milvusClient, MilvusProperties props) {
        this.milvusClient = milvusClient;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String collection = props.getCollection();
        try {
            R<Boolean> has =
                    milvusClient.hasCollection(
                            HasCollectionParam.newBuilder().withCollectionName(collection).build());
            if (has.getData()) {
                log.info("✓ Milvus collection '{}' 已存在, 跳过创建", collection);
                return;
            }
            createCollection(collection);
            log.info("✓ Milvus collection '{}' 已自动创建 + 索引就绪", collection);
        } catch (Exception e) {
            log.warn("Milvus collection 初始化失败(应用仍可启动): {}", e.getMessage());
        }
    }

    private void createCollection(String collection) throws Exception {
        // 1. 建集合
        CreateCollectionParam createParam =
                CreateCollectionParam.newBuilder()
                        .withCollectionName(collection)
                        .withDescription("RAG doc chunks V2")
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_ID)
                                        .withDataType(DataType.Int64)
                                        .withPrimaryKey(true)
                                        .withAutoID(true)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_DENSE)
                                        .withDataType(DataType.FloatVector)
                                        .withDimension(DENSE_DIM)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_SPARSE)
                                        .withDataType(DataType.SparseFloatVector)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_DOC_ID)
                                        .withDataType(DataType.Int64)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_CHUNK_ID)
                                        .withDataType(DataType.Int64)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_PAGE)
                                        .withDataType(DataType.Int32)
                                        .build())
                        .addFieldType(
                                FieldType.newBuilder()
                                        .withName(FIELD_TENANT)
                                        .withDataType(DataType.VarChar)
                                        .withMaxLength(32)
                                        .build())
                        .build();
        R<RpcStatus> createResp = milvusClient.createCollection(createParam);
        if (createResp.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("CreateCollection 失败: " + createResp.getMessage());
        }

        // 2. 建 dense 索引 (HNSW)
        R<RpcStatus> denseIdx =
                milvusClient.createIndex(
                        CreateIndexParam.newBuilder()
                                .withCollectionName(collection)
                                .withFieldName(FIELD_DENSE)
                                .withIndexType(IndexType.HNSW)
                                .withMetricType(MetricType.IP)
                                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                                .build());
        if (denseIdx.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("CreateIndex dense 失败: " + denseIdx.getMessage());
        }

        // 3. 建 sparse 索引 (SPARSE_INVERTED)
        R<RpcStatus> sparseIdx =
                milvusClient.createIndex(
                        CreateIndexParam.newBuilder()
                                .withCollectionName(collection)
                                .withFieldName(FIELD_SPARSE)
                                .withIndexType(IndexType.SPARSE_INVERTED_INDEX)
                                .withMetricType(MetricType.IP)
                                .withExtraParam("{}")
                                .build());
        if (sparseIdx.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("CreateIndex sparse 失败: " + sparseIdx.getMessage());
        }

        log.info("✓ Milvus collection '{}' schema + dense/sparse 索引创建完成", collection);
    }
}
