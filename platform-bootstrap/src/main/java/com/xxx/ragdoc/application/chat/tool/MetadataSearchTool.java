package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.RetrieveService;
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
 * PR-4 / EMS-PR4: metadata_search Tool — 强制 metadata filter 的检索 (与 semantic_search 的"概念查询"区别)。
 *
 * <p>关键守门: <b>必须</b>带至少一个 source/version/language filter; 不带 filter 时返 INVALID_ARGUMENT。 适合: "Nacos
 * 章节"、"v2.3 版本文档"、"zh 语言"。不适合: 概念查询 (用 semantic_search)。
 *
 * <p>底层委托 RetrieveService.retrieve(cmd, HYBRID); HYBRID = Dense + BM25 RRF, 让 metadata 限定 + 字面量加分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataSearchTool implements AgentTool<SearchInput, SearchOutput> {

    public static final String NAME = "metadata_search";
    public static final String VERSION = "v1";

    private final RetrieveService retrieveService;

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                VERSION,
                "元数据过滤检索: 必须带 source/version/language 至少一项, 在限定范围 hybrid 检索。"
                        + "适用: 版本文档 / 章节定位 / 产品文档。不适用: 概念查询 (用 semantic_search)。",
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
        SearchInput.SearchFilters f = input.filters();
        if ((f.source() == null && f.version() == null && f.language() == null)) {
            return ToolResult.failure(
                    context.requestId() + "-meta",
                    NAME,
                    VERSION,
                    ToolStatus.INVALID_ARGUMENT,
                    ToolError.of(
                            ErrorCode.TOOL_INVALID_ARGUMENT.code(),
                            "metadata_search 必须带 source/version/language 至少一项"),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }
        int userTopK = input.topK() == null ? 5 : input.topK();
        ChatCommand cmd =
                new ChatCommand(
                        input.query(), null, userTopK, f.source(), f.version(), f.language(), null);

        RetrieveService.RetrieveResult result;
        try {
            result = retrieveService.retrieve(cmd, Retriever.Mode.HYBRID, false);
        } catch (RuntimeException ex) {
            log.warn("tool.metadata_search.failed err={}", ex.toString());
            return ToolResult.failure(
                    context.requestId() + "-meta",
                    NAME,
                    VERSION,
                    ToolStatus.DEPENDENCY_UNAVAILABLE,
                    ToolError.dependencyError(
                            ErrorCode.TOOL_DEPENDENCY_UNAVAILABLE.code(),
                            "metadata 检索依赖暂不可用",
                            "milvus-or-embedding",
                            true),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }

        EvidenceSnapshot snap = result.evidenceSnapshot();
        List<Evidence> ctx = snap != null ? snap.finalContext() : List.of();
        if (ctx == null || ctx.isEmpty()) {
            return ToolResult.empty(
                    context.requestId() + "-meta",
                    NAME,
                    VERSION,
                    ToolError.of("EMPTY_RESULT", "metadata 检索未召回"),
                    System.currentTimeMillis() - t0,
                    java.util.Map.of());
        }
        int max = descriptor().maxResults();
        int original = ctx.size();
        List<Evidence> trimmed =
                original > max ? new ArrayList<>(ctx.subList(0, max)) : new ArrayList<>(ctx);
        return ToolResult.success(
                context.requestId() + "-meta",
                NAME,
                VERSION,
                new SearchOutput(
                        trimmed,
                        new SearchOutput.TruncationInfo(
                                trimmed.size() < original, original, trimmed.size())),
                System.currentTimeMillis() - t0,
                java.util.Map.of());
    }
}
