package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;

/**
 * Task 5 / V11 Hybrid Retrieval: 检索器端口。
 *
 * <p>与 {@link VectorStore} 的关系: VectorStore 仍保留写入(upsert/delete/count)与 fallback 检索; 本接口抽出"读取 +
 * 融合"语义, 让 RetrieveService 调它而不是直接调 VectorStore, 方便 dense-only / hybrid 路径切换 + RRF 融合层 + 未来扩展(eg.
 * 外部 BM25 引擎)。
 *
 * <p>实现:
 *
 * <ul>
 *   <li>{@code DenseRetriever} — 单路 BGE-M3 dense ANN, 包装现有 Milvus dense 检索
 *   <li>{@code SparseRetriever} — 单路 BM25 sparse, 包装 Milvus BM25 Function 检索
 *   <li>{@code RRFFusioner} — Reciprocal Rank Fusion(k=60), 融合 two rankings
 *   <li>{@code HybridRetriever} — 组合 Dense + Sparse + RRF (mode=HYBRID 走它)
 *   <li>{@code MilvusRetriever} — 按 {@link Mode} 路由到 dense 或 hybrid 路径 (Spring 主 bean)
 * </ul>
 *
 * <p>语义约定: 实现不持久化任何状态, 纯函数式: 给 query 拿候选。Permission Filter / 文档元数据过滤 全部走 {@link
 * VectorStore.MetadataFilter}, Retriever 透传给底层 Milvus。
 */
public interface Retriever {

    /** 检索模式。 */
    enum Mode {
        /** 单路 BGE-M3 dense ANN (生产基线, 兼容 V1-V10 老路径)。 */
        DENSE,
        /** dense + BM25 sparse → RRF(k=60) 融合 (V11 开 1.5x retrieval latency 换 recall)。 */
        HYBRID
    }

    /** 检索输入。所有字段非空除特别说明。 */
    record Query(
            EmbeddingResult embedding,
            String text,
            Long docId,
            int topK,
            VectorStore.MetadataFilter filter,
            /** null = 走全局 {@code rag.retrieve.mode} 默认值; 非 null = per-request override。 */
            Mode mode) {

        public Query {
            if (embedding == null) {
                throw new IllegalArgumentException("Query.embedding 不能为空");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Query.text 不能为空 (BM25 路需要)");
            }
            if (topK < 1) {
                throw new IllegalArgumentException("Query.topK 必须 >= 1, 当前=" + topK);
            }
        }
    }

    /**
     * 执行检索, 返回 {@code topK} 条命中(融合后降序)。
     *
     * <p>实现必须保证:
     *
     * <ul>
     *   <li>真实零命中返回 {@code List.of()}；基础设施失败必须抛出异常，禁止伪装成 NO_RECALL
     *   <li>filter 中的 {@code allowedDocIds} (V9 权限白名单) 严格透传给底层标量过滤
     * </ul>
     */
    List<ScoredChunk> search(Query q);
}
