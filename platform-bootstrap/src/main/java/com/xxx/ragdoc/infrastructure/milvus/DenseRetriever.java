package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 5: Dense 检索单路 — 包装 {@link MilvusVectorStore#searchDense}。
 *
 * <p>职责: 单纯跑 BGE-M3 dense ANN, 不做融合。是 HybridRetriever 的 dense sibling, 也可被 AB 实验直接调 (mode=DENSE
 * 走它)。
 *
 * <p>放 infrastructure.milvus 包 (合法调用同包 MilvusVectorStore 的 package-private 方法)。 上层 RetrieveService
 * 通过 {@link com.xxx.ragdoc.application.document.port.Retriever} 接口看它。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DenseRetriever {

    private final MilvusVectorStore vectorStore;

    /**
     * 单路 dense ANN 检索。
     *
     * @param expr Milvus 标量过滤表达式 (含 tenant_id + allowedDocIds 白名单); null=不过滤
     */
    public List<ScoredChunk> search(EmbeddingResult embedding, String expr, int topK) {
        return vectorStore.searchDense(embedding, expr, topK);
    }
}
