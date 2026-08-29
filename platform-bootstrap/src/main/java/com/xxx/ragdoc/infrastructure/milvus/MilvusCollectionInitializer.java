package com.xxx.ragdoc.infrastructure.milvus;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Milvus collection 初始化器(v2 API, BM25 Function)。
 *
 * <p>启动时幂等创建 collection + 索引; 已存在则跳过。
 *
 * <p>SDK 2.5.x schema (双路召回用 BM25 sparse + dense):
 *
 * <ul>
 *   <li>id (INT64, PK, auto-id)
 *   <li>dense_vector (FLOAT_VECTOR, dim=1024) — BGE-M3 dense
 *   <li>text (VARCHAR 4000, enable_analyzer=chinese) — chunk 文本, BM25 输入
 *   <li>sparse_bm25 (SPARSE_FLOAT_VECTOR) — 由 BM25 Function 自动算, 不手动写
 *   <li>document_id (INT64) — 过滤
 *   <li>chunk_id (INT64) — 回溯 MySQL chunks 表
 *   <li>page (INT32) — 引用回溯
 *   <li>tenant_id (VARCHAR 32) — V4 多租户
 * </ul>
 *
 * <p>Function: BM25(input=text → output=sparse_bm25), Milvus 2.5 原生分词+倒排, 中文用内置 chinese analyzer。
 *
 * <p>索引:
 *
 * <ul>
 *   <li>dense: HNSW (M=16, efConstruction=200, IP)
 *   <li>sparse_bm25: SPARSE_INVERTED_INDEX (BM25 metric)
 * </ul>
 *
 * <p>启动只负责“空环境创建”和“已有环境校验”，绝不删除已有 collection。schema 升级必须通过新 collection 回灌、校验和配置切换完成。
 */
@Slf4j
@Component
public class MilvusCollectionInitializer implements ApplicationRunner {

    public static final int DENSE_DIM = 1024;
    public static final int TEXT_MAX_LENGTH = 4000;
    public static final String FIELD_ID = "id";
    public static final String FIELD_DENSE = "dense_vector";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_SPARSE_BM25 = "sparse_bm25";
    public static final String FIELD_DOC_ID = "document_id";
    public static final String FIELD_GENERATION = "ingestion_generation";
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_PAGE = "page";
    public static final String FIELD_TENANT = "tenant_id";
    // V3 业务元数据标量字段(原 data-model.md L159 即要求 chunk_type 入 Milvus 做过滤, 此前漏建)
    // source/version/language/doc_type 支撑元数据过滤检索与按组件分组消融。
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_LOGICAL_DOCUMENT_KEY = "logical_document_key";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_DOC_TYPE = "doc_type";
    public static final String FIELD_CHUNK_TYPE = "chunk_type";
    public static final String BM25_FUNCTION_NAME = "text_to_bm25";

    /**
     * 旧 schema 用的 sparse 字段名(BGE-M3 sparse 占位), 用于检测老 collection 是否需要重建。 V3 后又新增 5 个标量, 老版 V2-C
     * collection 亦需重建。
     */
    private static final String LEGACY_OLD_SPARSE_FIELD = "sparse_vector";

    private final MilvusClientV2 milvusClientV2;
    private final MilvusProperties props;

    public MilvusCollectionInitializer(MilvusClientV2 milvusClientV2, MilvusProperties props) {
        this.milvusClientV2 = milvusClientV2;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String collection = props.getCollection();
        try {
            boolean has =
                    milvusClientV2.hasCollection(
                            HasCollectionReq.builder().collectionName(collection).build());
            if (has) {
                if (needsSchemaMigration(collection)) {
                    String message =
                            "Milvus collection '"
                                    + collection
                                    + "' schema 不兼容；请创建新 collection、全量回灌并切换 MILVUS_COLLECTION，应用不会自动删除数据";
                    if (props.isFailOnSchemaMismatch()) {
                        throw new IllegalStateException(message);
                    }
                    log.error(message);
                }
                log.info("✓ Milvus collection '{}' 已存在，校验完成", collection);
                return;
            }
            createCollection(collection);
            log.info("✓ Milvus collection '{}' 已自动创建 + dense/sparse_bm25 索引就绪", collection);
        } catch (Exception e) {
            if (props.isFailOnSchemaMismatch()) {
                throw e instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Milvus collection 初始化失败", e);
            }
            log.error("Milvus collection 初始化失败，检索可能不可用: {}", e.getMessage(), e);
        }
    }

    /** 判断已存在的 collection 是否需要迁移重建: 看是否缺 BM25 或 V3 元数据字段。 */
    private boolean needsSchemaMigration(String collection) {
        try {
            DescribeCollectionResp resp =
                    milvusClientV2.describeCollection(
                            DescribeCollectionReq.builder().collectionName(collection).build());
            java.util.List<String> fieldNames = resp.getFieldNames();
            boolean hasLegacySparse = fieldNames.contains(LEGACY_OLD_SPARSE_FIELD);
            boolean hasBm25 = fieldNames.contains(FIELD_SPARSE_BM25);
            boolean hasSource = fieldNames.contains(FIELD_SOURCE);
            boolean hasChunkType = fieldNames.contains(FIELD_CHUNK_TYPE);
            boolean hasLogicalDocumentKey = fieldNames.contains(FIELD_LOGICAL_DOCUMENT_KEY);
            boolean hasGeneration = fieldNames.contains(FIELD_GENERATION);
            boolean needsMigrate =
                    hasLegacySparse
                            || !hasBm25
                            || !hasSource
                            || !hasChunkType
                            || !hasLogicalDocumentKey
                            || !hasGeneration;
            log.info(
                    "milvus.schema_check fields={} hasLegacySparse={} hasBm25={} hasSource={} hasChunkType={} -> needsMigrate={}",
                    fieldNames,
                    hasLegacySparse,
                    hasBm25,
                    hasSource,
                    hasChunkType,
                    needsMigrate);
            return needsMigrate;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 Milvus collection schema: " + collection, e);
        }
    }

    private void createCollection(String collection) {
        // ===== Schema =====
        // v2 API: 用 AddFieldReq (不是 FieldType), schema.addField() 接 AddFieldReq。
        CreateCollectionReq.CollectionSchema schema =
                CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_ID)
                        .dataType(DataType.Int64)
                        .isPrimaryKey(true)
                        .autoID(true)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_DENSE)
                        .dataType(DataType.FloatVector)
                        .dimension(DENSE_DIM)
                        .build());
        // text 字段: 必须开 analyzer 才能跑 BM25 分词。
        // Milvus 2.5 内置 chinese analyzer, 不需 jieba 外挂。
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_TEXT)
                        .dataType(DataType.VarChar)
                        .maxLength(TEXT_MAX_LENGTH)
                        .enableAnalyzer(true)
                        .analyzerParams(java.util.Map.of("type", "chinese"))
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_SPARSE_BM25)
                        .dataType(DataType.SparseFloatVector)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_DOC_ID)
                        .dataType(DataType.Int64)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_GENERATION)
                        .dataType(DataType.Int32)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_CHUNK_ID)
                        .dataType(DataType.Int64)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_PAGE)
                        .dataType(DataType.Int32)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_TENANT)
                        .dataType(DataType.VarChar)
                        .maxLength(32)
                        .build());
        // ===== V3 业务元数据标量字段, 用于标量过滤检索 =====
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_SOURCE)
                        .dataType(DataType.VarChar)
                        .maxLength(32)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_VERSION)
                        .dataType(DataType.VarChar)
                        .maxLength(16)
                        .build()); // Milvus SDK 2.5 不支持 nullable(true); version 空时存空串, 读侧映射回 null
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_LOGICAL_DOCUMENT_KEY)
                        .dataType(DataType.VarChar)
                        .maxLength(128)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_LANGUAGE)
                        .dataType(DataType.VarChar)
                        .maxLength(8)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_DOC_TYPE)
                        .dataType(DataType.VarChar)
                        .maxLength(16)
                        .build());
        schema.addField(
                io.milvus.v2.service.collection.request.AddFieldReq.builder()
                        .fieldName(FIELD_CHUNK_TYPE)
                        .dataType(DataType.VarChar)
                        .maxLength(16)
                        .build());

        // BM25 Function: 自动从 text 算 sparse_bm25, 插入时不需手动写 sparse
        schema.addFunction(
                CreateCollectionReq.Function.builder()
                        .name(BM25_FUNCTION_NAME)
                        .functionType(FunctionType.BM25)
                        .inputFieldNames(List.of(FIELD_TEXT))
                        .outputFieldNames(List.of(FIELD_SPARSE_BM25))
                        .build());

        // ===== 索引 =====
        IndexParam denseIdx =
                IndexParam.builder()
                        .fieldName(FIELD_DENSE)
                        .indexType(IndexParam.IndexType.HNSW)
                        .metricType(IndexParam.MetricType.IP)
                        .extraParams(java.util.Map.of("M", 16, "efConstruction", 200))
                        .build();
        IndexParam sparseIdx =
                IndexParam.builder()
                        .fieldName(FIELD_SPARSE_BM25)
                        .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                        .metricType(IndexParam.MetricType.BM25)
                        .build();
        // 标量索引注意:
        //   Milvus 2.5 的 STL_SORT 仅支持数值字段; 对 VARCHAR(source/tenant/language 等) 不能用。
        //   数值标量(document_id) 走 STL_SORT 加速等值过滤; 字符串标量在此版本先不加索引,
        //   Milvus 用 brute-force 过滤, V2 数据规模(<10w 行)下完全够用。
        //   V4+ 若要给字符串标量加索引, 用 Milvus 2.6+ 的 INVERTED 索引(本版 SDK 2.5 暂不支持)。
        IndexParam docIdIdx =
                IndexParam.builder()
                        .fieldName(FIELD_DOC_ID)
                        .indexType(IndexParam.IndexType.STL_SORT)
                        .build();

        milvusClientV2.createCollection(
                CreateCollectionReq.builder()
                        .collectionName(collection)
                        .description("RAG doc chunks V3 (dense + BM25 sparse + RRF + metadata)")
                        .collectionSchema(schema)
                        .indexParams(List.of(denseIdx, sparseIdx, docIdIdx))
                        .build());
    }
}
