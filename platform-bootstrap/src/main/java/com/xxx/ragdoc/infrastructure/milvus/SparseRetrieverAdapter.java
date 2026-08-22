package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.chat.port.SparseSearchPort;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-4: 把 infrastructure 的 {@link SparseRetriever} (Milvus BM25) 暴露为 application {@link
 * SparseSearchPort}。
 *
 * <p>把 ACL {@code allowedDocIds} (null=admin / 空=NO_RECALL) + tenant + source/version 翻译成 Milvus
 * {@code expr}。 复用既有 {@link MilvusFilterExprBuilder} expr 生成逻辑保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparseRetrieverAdapter implements SparseSearchPort {

    private final SparseRetriever sparseRetriever;
    // P1 修复(hybrid 启用后暴露的存量 bug): MilvusFilterExprBuilder 是纯静态工具类
    // (私有构造器), 不能作 bean 注入 — dense 模式下本 Adapter 从未加载所以一直没炸。


    @Override
    public List<ScoredChunk> search(
            String queryText,
            Collection<Long> allowedDocIds,
            String tenantId,
            String source,
            String version,
            int topK) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        if (allowedDocIds != null && allowedDocIds.isEmpty()) {
            // deny-by-default sentinel — 一条都不查 (避免 ALWAYS_FALSE 仍走 Milvus 浪费 RPC)
            return List.of();
        }
        // 构造 MetadataFilter (复用既有 expr builder) — 第一版只支持 source/version + ACL 字段
        com.xxx.ragdoc.application.document.port.VectorStore.MetadataFilter filter =
                new com.xxx.ragdoc.application.document.port.VectorStore.MetadataFilter(
                        source, version, null, tenantId, allowedDocIds);
        String expr = MilvusFilterExprBuilder.build(null, filter);
        try {
            return sparseRetriever.search(queryText, expr, topK);
        } catch (RuntimeException ex) {
            // SparseRetriever 内部已 fail-soft 返回 empty; 此处只兜底未捕获异常
            log.warn(
                    "sparse_retriever.uncaught_exception tenant={} query_len={} err={}",
                    tenantId,
                    queryText == null ? 0 : queryText.length(),
                    ex.toString());
            throw ex;
        }
    }
}
