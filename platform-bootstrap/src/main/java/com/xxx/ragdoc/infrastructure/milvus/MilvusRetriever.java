package com.xxx.ragdoc.infrastructure.milvus;

import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.infrastructure.milvus.MilvusFilterExprBuilder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 5: {@link Retriever} 主实现 — 按 query.mode 路由到 dense / hybrid 路径。
 *
 * <p>当 {@link Retriever.Query#mode()} 非空时, per-request override 生效 (AB 实验用);
 * null 时退回 {@link RetrieveProperties#getMode()} 全局默认。
 *
 * <p>放 infrastructure.milvus 是合规的: 它本身不属于 application (引用同包 DenseRetriever 等),
 * 但实现 application 层 Retriever 接口; RetrieveService(application) 通过接口看本类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusRetriever implements Retriever {

    private final DenseRetriever denseRetriever;
    private final HybridRetriever hybridRetriever;
    private final RetrieveProperties retrieveProps;

    @Override
    public List<ScoredChunk> search(Query q) {
        // 解析 mode: per-request > 全局默认
        Mode effective =
                q.mode() != null ? q.mode() : Mode.valueOf(retrieveProps.getMode().name());

        // buf Milvus expr: docId + MetadataFilter (含 V9 权限白名单 + tenant)
        String expr = MilvusFilterExprBuilder.build(q.docId(), q.filter());

        try {
            List<ScoredChunk> result;
            if (effective == Mode.HYBRID) {
                result =
                        hybridRetriever.search(
                                q.embedding(), q.text(), expr, q.topK());
            } else {
                result = denseRetriever.search(q.embedding(), expr, q.topK());
            }
            log.debug(
                    "retriever.search mode={}, topK={}, expr={}, hits={}",
                    effective,
                    q.topK(),
                    expr,
                    result.size());
            return result;
        } catch (Exception e) {
            // 检索 fail 不阻断 chat 主流程 (上游 RetrieveService 有 fallback path)
            log.warn(
                    "retriever.search_failed mode={}, topK={}, error={}",
                    effective,
                    q.topK(),
                    e.getMessage());
            return List.of();
        }
    }
}
