package com.xxx.ragdoc.application.chat.port;

import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;

/**
 * PR-4 / EMS-PR4: 稀疏关键词检索端口 (BM25-style)。让 application 层 (KeywordSearchTool) 通过此 port 调用
 * infrastructure 层的 SparseRetriever / Milvus BM25 通路, 不破坏 ArchUnit "application 不依赖 infrastructure"
 * 规则。
 *
 * <p>port 只接收已经过 ACL sentinel 决策的 {@code allowedDocIds} (null = admin 全租户 / 空 set = NO_RECALL); 实现
 * (SparseRetrieverAdapter) 把 allowedDocIds 翻译成 Milvus {@code expr} 字符串。
 *
 * <p>第 1 版只做 Milvus BM25 (SparseRetriever); 未来可换 ES / Lucene 不动 application。
 */
public interface SparseSearchPort {

    /**
     * @param queryText 用户原文 (未规范化 OK, BM25 内部分词)
     * @param allowedDocIds ACL 已决定的可见 docIds (null=admin / 空集=NO_RECALL)
     * @param tenantId 必填, filter tenant_id
     * @param source 可选, 限定来源
     * @param version 可选, 限定版本
     * @param topK 服务端会与 ToolDescriptor.maxResults 取小
     * @return BM25 命中的 chunkId+score 列表 (按 score 倒序, 可能空 = Empty)
     */
    List<ScoredChunk> search(
            String queryText,
            java.util.Collection<Long> allowedDocIds,
            String tenantId,
            String source,
            String version,
            int topK);
}
