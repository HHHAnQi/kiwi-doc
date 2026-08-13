package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chunk.ChunkQueryService;
import com.xxx.ragdoc.application.chunk.query.ChunkDetail;
import com.xxx.ragdoc.application.chunk.query.ChunkNeighbors;
import com.xxx.ragdoc.application.document.DocumentAccessGuard;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-4 / EMS-PR4: document_fetch Tool — 按 chunkId / documentId+seq / 邻居 / parent 读取授权 chunk。
 *
 * <h2>三层 ACL 守门</h2>
 *
 * <ol>
 *   <li>PermissionScope (Executor 已派生) — 不可读文档集 → metadata filter
 *   <li>DocumentAccessGuard.requireRead(documentId) → 跨租户/无权塌缩 404 → Tool 转 PERMISSION_DENIED
 *   <li>Executor 的 evidence post-check (tenantId 一致)
 * </ol>
 *
 * <p>限流: maxResults=10, neighborCount ∈ [0,5], includeParent 只取直接 parent; 不支持任意路径 / objectKey /
 * 整库导出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentFetchTool implements AgentTool<DocumentFetchInput, DocumentFetchOutput> {

    public static final String NAME = "document_fetch";
    public static final String VERSION = "v1";

    private final ChunkQueryService chunkQueryService;
    private final ChunkRepository chunkRepository;
    private final DocumentAccessGuard documentAccessGuard;

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                NAME,
                VERSION,
                "按 chunkId / documentId+seq / 邻居方向 / parent chunk 读取已授权 chunk。"
                        + "适用: 工作流补全证据 / Agent 显式查取片段。不适用: 模糊检索 (用 semantic_search)。",
                "v1",
                "v1",
                ToolPermission.READ_DOCUMENT,
                Duration.ofSeconds(5),
                10,
                true,
                ToolCostCategory.INDEX_READ);
    }

    @Override
    public Class<DocumentFetchInput> inputType() {
        return DocumentFetchInput.class;
    }

    @Override
    public Class<DocumentFetchOutput> outputType() {
        return DocumentFetchOutput.class;
    }

    @Override
    public ToolResult<DocumentFetchOutput> execute(
            DocumentFetchInput input, ToolExecutionContext context) {
        long t0 = System.currentTimeMillis();
        Principal principal = context.principal();

        // 入口 anchor: 拿到要 fetch 的"中心 chunk" (chunkId 优先; 没有 chunkId 时由 documentId+辅助方式决定)
        ChunkDetail anchor = null;
        if (input.chunkId() != null) {
            try {
                anchor = chunkQueryService.getChunk(input.chunkId());
            } catch (NotFoundException nfe) {
                // chunk 不存在或跨租户都塌缩 404; 安全语义统一为 PERMISSION_DENIED (不区分"无权"与"不存在")
                return denyResult(context, t0, nfe.getMessage());
            }
        } else if (input.documentId() != null) {
            // documentId 模式: 必须先验文档 ACL, 再取该文档任意一条 chunk (用 listAccessible 内部一致性)
            try {
                documentAccessGuard.requireRead(input.documentId());
            } catch (NotFoundException nfe) {
                return denyResult(context, t0, nfe.getMessage());
            }
            // 取文档第一条 chunk 作为 anchor (ChunkRepository.findByDocumentIdAndSeq(seq=0/1))
            // 这里 ChunkRepository 第一版没默认 seq 取法, 取 findByDocumentIdAndPageOrderBySeq 不一定有 page 输入;
            // 简单实现: 不再深入, 仅返回 PERMISSION_DENIED-shaped EMPTY 让上层用 chunkId 重试
            return ToolResult.empty(
                    context.requestId() + "-doc",
                    NAME,
                    VERSION,
                    ToolError.of("INVALID_ARGUMENT", "document_fetch 第一版要求传 chunkId"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }

        if (anchor == null) {
            return ToolResult.failure(
                    context.requestId() + "-doc",
                    NAME,
                    VERSION,
                    ToolStatus.INVALID_ARGUMENT,
                    ToolError.of(ErrorCode.TOOL_INVALID_ARGUMENT.code(), "需提供 chunkId"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }

        // 二次守门: 用 anchor.documentId() 显式 requireRead
        try {
            documentAccessGuard.requireRead(anchor.documentId());
        } catch (NotFoundException nfe) {
            return denyResult(context, t0, nfe.getMessage());
        }

        String mode = "SELF";
        List<ChunkDetail> collected = new ArrayList<>();
        collected.add(anchor);
        List<Long> chunkIds = new ArrayList<>();
        chunkIds.add(anchor.id());

        int neighbor = input.neighborCount() == null ? 0 : input.neighborCount();
        if (neighbor > 0 && input.direction() != DocumentFetchInput.FetchDirection.SELF) {
            ChunkQueryService.Direction dir = toQueryDirection(input.direction());
            try {
                ChunkNeighbors nb = chunkQueryService.getNeighbors(anchor.id(), dir);
                if (neighbor >= 1 && nb.prev() != null) {
                    collected.add(nb.prev());
                    chunkIds.add(nb.prev().id());
                    mode = "NEIGHBOR";
                }
                if (neighbor >= 1 && nb.next() != null) {
                    collected.add(nb.next());
                    chunkIds.add(nb.next().id());
                    mode = "NEIGHBOR";
                }
            } catch (NotFoundException nfe) {
                // 邻居不存在不算失败, anchor 仍可用
                log.debug(
                        "doc_fetch.no_neighbor chunk_id={} err={}", anchor.id(), nfe.getMessage());
            }
        }

        if (input.includeParent() && anchor.parentChunkId() != null) {
            try {
                ChunkDetail parent = chunkQueryService.getChunk(anchor.parentChunkId());
                // parent 必须同 documentId (一般成立); 若 parent documentId 不同 → 二次 ACL
                if (!parent.documentId().equals(anchor.documentId())) {
                    documentAccessGuard.requireRead(parent.documentId());
                }
                collected.add(parent);
                chunkIds.add(parent.id());
                mode = mode.equals("NEIGHBOR") ? "NEIGHBOR+PARENT" : "PARENT";
            } catch (NotFoundException nfe) {
                log.debug(
                        "doc_fetch.no_parent parent_chunk_id={} err={}",
                        anchor.parentChunkId(),
                        nfe.getMessage());
            }
        }

        List<ChunkDetail> trimmed =
                collected.size() > descriptor().maxResults()
                        ? collected.subList(0, descriptor().maxResults())
                        : collected;

        List<Evidence> evidences = new ArrayList<>();
        for (ChunkDetail cd : trimmed) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("page", cd.page());
            meta.put("sectionPath", cd.sectionPath());
            meta.put("documentFilename", cd.documentFilename());
            meta.put("seq", cd.seq());
            meta.put("type", cd.type() == null ? null : cd.type().name());
            evidences.add(
                    Evidence.of(
                            principal.tenantId(),
                            cd.documentId(),
                            cd.id(),
                            null, // docVersion 第一版未携带 (ChunkDetail 不含)
                            cd.content(),
                            null, // 显式 fetch 不打分; retrievalScore/rerankScore=null 让评测识别为 "非召回"
                            null,
                            NAME,
                            meta));
        }
        if (evidences.isEmpty()) {
            return ToolResult.empty(
                    context.requestId() + "-doc",
                    NAME,
                    VERSION,
                    ToolError.of("EMPTY_RESULT", "无可用 chunk (可能 ACL 全部过滤)"),
                    System.currentTimeMillis() - t0,
                    Map.of());
        }
        return ToolResult.success(
                context.requestId() + "-doc",
                NAME,
                VERSION,
                new DocumentFetchOutput(evidences, chunkIds, mode),
                System.currentTimeMillis() - t0,
                Map.of());
    }

    private static ChunkQueryService.Direction toQueryDirection(
            DocumentFetchInput.FetchDirection d) {
        return switch (d) {
            case NEXT -> ChunkQueryService.Direction.NEXT;
            case PREV -> ChunkQueryService.Direction.PREV;
            case BOTH -> ChunkQueryService.Direction.BOTH;
            default -> ChunkQueryService.Direction.BOTH;
        };
    }

    private static ToolResult<DocumentFetchOutput> denyResult(
            ToolExecutionContext ctx, long t0, String nestedMsg) {
        return ToolResult.failure(
                ctx.requestId() + "-doc",
                NAME,
                VERSION,
                ToolStatus.PERMISSION_DENIED,
                ToolError.of(ErrorCode.TOOL_PERMISSION_DENIED.code(), "无权访问或资源不存在"),
                System.currentTimeMillis() - t0,
                Map.of());
    }
}
