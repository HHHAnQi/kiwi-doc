package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.RetrieveService.RetrieveResult;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.common.exception.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-4 / EMS-PR4: semantic_search Tool — 用 Dense 向量召回 + 已有 ACL / Rerank 走通道。
 *
 * <p>底层委托 {@link RetrieveService#retrieve(ChatCommand, Retriever.Mode, Boolean)} 强制 mode=DENSE /
 * enhance=false。 复用既有 AccessScope sentinel / MetadataFilter / Milvus 调用 / Reranker fallback;
 * 不重造检索.Layout。
 *
 * <p>输出: 把 {@link EvidenceSnapshot#finalContext()} 转成 {@link SearchOutput}。finalContext 与 citations
 * 同序同长, 适合作为 Agent 引用证据 (而不是 raw 初召 — 避免引入未经 context 包装的内容)。
 *
 * <p>ACL 由 RetrieveService 内部完成; 本 Tool 不持有 Principal / tenantId / docIds 字段。 ToolExecutor 还会做
 * evidence post-check 二次校验 tenantId 一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticSearchTool implements AgentTool<SearchInput, SearchOutput> {

    public static final String NAME = "semantic_search";
    public static final String VERSION = "v1";

    private final RetrieveService retrieveService;

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                VERSION,
                "语义检索: 用 Dense 向量在知识库检索 chunks, 返回 Evidence。"
                        + "适用: 概念/原理/事实类问题。不适用: 精确元数据查询 (用 metadata_search), A/B 比较 (用 comparison_workflow)。",
                "v1",
                "v1",
                ToolPermission.READ_RETRIEVE,
                Duration.ofSeconds(10),
                20,
                true,
                ToolCostCategory.EMBEDDING);
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
        // 把 SearchInput + filters 映射到 ChatCommand (RetrieveService 已支持 source/version/language)
        SearchInput.SearchFilters f = input.filters();
        ChatCommand cmd =
                new ChatCommand(
                        input.query(),
                        null, // docId (Tool 第一版不暴露, 由 metadata_search / doc_fetch 处理)
                        userTopK,
                        f.source(),
                        f.version(),
                        f.language(),
                        null);

        RetrieveResult result;
        try {
            result = retrieveService.retrieve(cmd, Retriever.Mode.DENSE, false);
        } catch (RuntimeException ex) {
            // 检索下游 (Milvus / Embedding) 真失败 — 转 DEPENDENCY_UNAVAILABLE 而不是 EMPTY
            log.warn(
                    "tool.semantic_search.retrieve_failed call_id_hint={} query_len={} err={}",
                    context.requestId(),
                    input.query().length(),
                    ex.toString());
            return ToolResult.failure(
                    context.requestId() + "-sem",
                    NAME,
                    VERSION,
                    ToolStatus.DEPENDENCY_UNAVAILABLE,
                    ToolError.dependencyError(
                            ErrorCode.TOOL_DEPENDENCY_UNAVAILABLE.code(),
                            "语义检索依赖暂不可用",
                            "milvus-or-embedding",
                            ToolStatus.DEPENDENCY_UNAVAILABLE.retryable()),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }

        // 把 finalContext evidence 转成 output
        EvidenceSnapshot snap = result.evidenceSnapshot();
        List<Evidence> finalCtx = snap != null ? snap.finalContext() : List.of();
        if (finalCtx == null || finalCtx.isEmpty()) {
            // NO_RECALL / ACL deny 都走这里: RetrieveService 内部 AccessScope 已 sentinel 短路
            return ToolResult.empty(
                    context.requestId() + "-sem",
                    NAME,
                    VERSION,
                    ToolError.of("EMPTY_RESULT", "语义检索未召回可引用的证据"),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }

        // 应用 ToolDescriptor.maxResults 截断 (服务端硬上限; 与 query topK 二者取小)
        int max = descriptor().maxResults();
        int originalCount = finalCtx.size();
        List<Evidence> trimmed = finalCtx.size() > max ? finalCtx.subList(0, max) : finalCtx;
        // copy 防止外部 mutate
        trimmed = new ArrayList<>(trimmed);
        boolean truncated = trimmed.size() < originalCount;
        SearchOutput out =
                new SearchOutput(
                        trimmed,
                        new SearchOutput.TruncationInfo(truncated, originalCount, trimmed.size()));
        return ToolResult.success(
                context.requestId() + "-sem",
                NAME,
                VERSION,
                out,
                System.currentTimeMillis() - t0,
                java.util.Map.of());
    }
}
