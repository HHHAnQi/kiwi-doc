package com.xxx.ragdoc.infrastructure.milvus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索模式配置(V2-C hybrid feature flag)。
 *
 * <p>控制 {@code MilvusVectorStore.search} 走哪条路:
 *
 * <ul>
 *   <li>{@code dense} (默认): 只跑 BGE-M3 dense ANN, 当前生产基线(数据规模不足时 hybrid 反而劣化)。
 *   <li>{@code hybrid}: 跑 dense + BM25 RRF 融合, 设计文档 chat/spec.md L64 既定方案。 需要充足数据规模(BM25 IDF
 *       才有统计意义)与较干净 chunk 文本配合, 否则反而劣化。
 * </ul>
 *
 * <p>切换: {@code application.yml} 设 {@code rag.retrieve.mode: dense|hybrid} 或环境变量 {@code
 * RAG_RETRIEVE_MODE}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.retrieve")
public class RetrieveProperties {
    /** 检索模式: dense(默认) 或 hybrid。 */
    private Mode mode = Mode.DENSE;

    public enum Mode {
        DENSE,
        HYBRID,
    }
}
