package com.xxx.ragdoc.application.chat.port;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import java.util.List;

/**
 * Embedding 端口(可替换实现: 本地 BGE-M3 / OpenAI / DashScope)。
 *
 * <p>V2 默认实现: {@code OpenAiCompatibleEmbeddingClient} 调 text-embeddings-inference BGE-M3 服务。
 */
public interface EmbeddingClient {

    /**
     * 批量 embed(性能优化: 比循环单条快 10x+; TikaParsingTrigger 解析后一次 embed 整文档的所有 chunks)。
     *
     * @param texts 文本列表(已切片)
     * @return 与输入顺序对齐的 EmbeddingResult 列表
     */
    List<EmbeddingResult> embedBatch(List<String> texts);

    /** 单条 embed(查询时用; 召回 query 用)。 */
    EmbeddingResult embed(String text);
}
