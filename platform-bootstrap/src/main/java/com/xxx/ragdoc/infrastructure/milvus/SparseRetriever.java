package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 5: BM25 sparse 检索单路 — 包装 {@link MilvusVectorStore#searchSparseBM25}。
 *
 * <p>职责: 单纯跑 BM25 倒排索引查询, 不做融合。HybridRetriever 的 sparse sibling。
 *
 * <p>注意: Milvus 内置中文 analyzer, 查询文本是一段自然语言 (不需要预先分词)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparseRetriever {

    private final MilvusVectorStore vectorStore;

    /**
     * 单路 BM25 sparse 检索。
     *
     * @param queryText 查询原文 (Milvus 内部分词)
     * @param expr 标量过滤表达式; null=不过滤
     */
    public List<ScoredChunk> search(String queryText, String expr, int topK) {
        return vectorStore.searchSparseBM25(queryText, expr, topK);
    }
}
