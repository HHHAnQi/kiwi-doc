package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.Retriever;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索模式配置(V2-C hybrid feature flag, Task 5 加 RRF 参数)。
 *
 * <p>控制 {@code MilvusVectorStore.search} 与 {@link Retriever} 默认走哪条路:
 *
 * <ul>
 *   <li>{@code dense} (默认): 只跑 BGE-M3 dense ANN, 当前生产基线(数据规模不足时 hybrid 反而劣化)。
 *   <li>{@code hybrid}: 跑 dense + BM25 RRF 融合, 设计文档 chat/spec.md L64 既定方案。 需要充足数据规模(BM25 IDF
 *       才有统计意义)与较干净 chunk 文本配合, 否则反而劣化。
 * </ul>
 *
 * <p>切换: {@code application.yml} 设 {@code rag.retrieve.mode: dense|hybrid} 或环境变量 {@code
 * RAG_RETRIEVE_MODE}。Task 5 起 AB 实验接口可在 per-request override。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.retrieve")
public class RetrieveProperties {
    /** 检索模式: dense(默认) 或 hybrid。 */
    private Mode mode = Mode.DENSE;

    /** RRF 融合常数, 默认 60 (与 chat/spec.md 公式一致)。 */
    private Rrf rrf = new Rrf();

    /** 单路召回宽度倍数 (单路取 topK * candidatePool 给 RRF 留排序空间)。默认 4。 */
    private int candidatePool = 4;

    /** Task 5: RRF 融合参数, 暴露给 ablation 实验。 */
    @Data
    public static class Rrf {
        /** RRF k 常数 (公式 score = sum 1/(k + rank)); 默认 60。 */
        private int k = 60;
    }

    public enum Mode {
        DENSE,
        HYBRID,
    }

    /** 把内部 enum 转 {@link Retriever.Mode}, 让 Retriever 实现不引用本类。 */
    public Retriever.Mode toRetrieverMode() {
        return mode == Mode.HYBRID ? Retriever.Mode.HYBRID : Retriever.Mode.DENSE;
    }
}
