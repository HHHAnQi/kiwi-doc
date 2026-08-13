package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.port.SparseSearchPort;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.Chunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-4 / EMS-PR4: keyword_search Tool — 独立 BM25 关键词检索 (区别于 semantic_search 的向量召回)。
 *
 * <p>底层委托 {@link SparseSearchPort} → Milvus BM25 sparse_vector; ACL 由本 Tool 显式传 allowedDocIds 给
 * adapter, adapter 翻译成 Milvus expr (复用 MilvusFilterExprBuilder)。
 *
 * <p>适用: 错误码 (AUTH_EXPIRED / 5002)、产品名精确匹配、API 名引用等"非语义、强字面"场景。 不适用: 概念性问题 (用 semantic_search)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordSearchTool implements AgentTool<SearchInput, SearchOutput> {

    public static final String NAME = "keyword_search";
    public static final String VERSION = "v1";
    private static final int MAX_HITS_BEHIND_FILTER = 50; // 内部召回过 max 后由 descriptor.maxResults 截

    private final SparseSearchPort sparseSearchPort;
    private final ChunkRepository chunkRepository;
    private final com.xxx.ragdoc.application.auth.PermissionResolverPort permissionResolver;

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                VERSION,
                "关键词检索: 用 BM25 在 chunk 文本上做字面量检索。"
                        + "适用: 错误码 / API 名 / 函数名 / 精确产品名。不适用: 概念/原理 (用 semantic_search)。",
                "v1",
                "v1",
                ToolPermission.READ_RETRIEVE,
                Duration.ofSeconds(10),
                20,
                true,
                ToolCostCategory.INDEX_READ);
    }

    @Override
    public Class<SearchInput> inputType() {
        return SearchInput.class;
    }

    @Override
    public Class<SearchOutput> outputType() {
        return SearchOutput.class;
    }

    @Override
    public ToolResult<SearchOutput> execute(SearchInput input, ToolExecutionContext context) {
        long t0 = System.currentTimeMillis();
        int userTopK = input.topK() == null ? 5 : input.topK();
        int fetchK =
                Math.min(MAX_HITS_BEHIND_FILTER, Math.max(userTopK, descriptor().maxResults()));

        // 从 ACL scope 派生 allowedDocIds (与 RetrieveService 走同一 PermissionResolverPort)
        Principal principal = AuthContext.currentPrincipal();
        com.xxx.ragdoc.application.auth.AccessScope scope =
                permissionResolver.resolveAccessScope(principal);
        java.util.Collection<Long> allowed =
                scope.isUnrestrictedWithinTenant() ? null : scope.allowedDocumentIds();
        if (!scope.isUnrestrictedWithinTenant() && allowed != null && allowed.isEmpty()) {
            // deny-by-default → EMPTY (没调下游)
            return ToolResult.empty(
                    context.requestId() + "-kw",
                    NAME,
                    VERSION,
                    ToolError.of("PERMISSION_DENIED", "当前用户无可读文档"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }

        SearchInput.SearchFilters f = input.filters();
        List<ScoredChunk> hits;
        try {
            hits =
                    sparseSearchPort.search(
                            input.query(),
                            allowed,
                            principal.tenantId(),
                            f.source(),
                            f.version(),
                            fetchK);
        } catch (RuntimeException ex) {
            log.warn(
                    "tool.keyword_search.failed query_len={} err={}",
                    input.query().length(),
                    ex.toString());
            return ToolResult.failure(
                    context.requestId() + "-kw",
                    NAME,
                    VERSION,
                    ToolStatus.DEPENDENCY_UNAVAILABLE,
                    ToolError.dependencyError(
                            ErrorCode.TOOL_DEPENDENCY_UNAVAILABLE.code(),
                            "关键词检索依赖暂不可用",
                            "milvus",
                            true),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }

        if (hits == null || hits.isEmpty()) {
            return ToolResult.empty(
                    context.requestId() + "-kw",
                    NAME,
                    VERSION,
                    ToolError.of("EMPTY_RESULT", "关键词检索未命中"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }

        // 回查 chunk 全文 (与 RetrieveService 相同模式), 转 Evidence
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();
        Map<Long, Chunk> chunkMap = new HashMap<>();
        for (Chunk c : chunkRepository.findByIdIn(ids)) chunkMap.put(c.id(), c);

        List<Evidence> out = new ArrayList<>();
        for (ScoredChunk h : hits) {
            Chunk c = chunkMap.get(h.chunkId());
            if (c == null) continue; // chunk meta 缺失 → 跳过 (与 RetrieveService 一致)
            Map<String, Object> meta = new HashMap<>();
            meta.put("page", c.page());
            meta.put("sectionPath", c.sectionPath());
            Evidence ev =
                    Evidence.of(
                            principal.tenantId(),
                            c.documentId(),
                            c.id(),
                            f.version(),
                            c.content(),
                            (double) h.score(),
                            null, // BM25 不走 rerank
                            NAME,
                            meta);
            out.add(ev);
            if (out.size() >= userTopK) break;
        }
        if (out.isEmpty()) {
            return ToolResult.empty(
                    context.requestId() + "-kw",
                    NAME,
                    VERSION,
                    ToolError.of("EMPTY_RESULT", "BM25 命中但 chunk 元数据缺失"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }
        return ToolResult.success(
                context.requestId() + "-kw",
                NAME,
                VERSION,
                new SearchOutput(
                        out, new SearchOutput.TruncationInfo(false, hits.size(), out.size())),
                System.currentTimeMillis() - t0,
                Map.of());
    }
}
