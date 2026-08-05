package com.xxx.ragdoc.application.chat.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 统一证据视图 (PR-1 / EMS-PR1)。
 *
 * <p>把所有检索 / 工具产出的命中文本归一为不可变 {@code Evidence}, 用于评测、Trace 还原与回放。
 *
 * <h2>不变量 (Agentic RAG 升级硬约束)</h2>
 *
 * <ul>
 *   <li>不可变 record; tenantId <b>不允许</b> 由 LLM 或客户端修改, 由服务端 Principal 注入。
 *   <li>{@code evidenceId} = sha256({@code tenantId | documentId | chunkId | contentHash}) 的 hex,
 *       可跨 Trace/Response 一致映射同一条物理证据。
 *   <li>{@code contentHash} = sha256({@code content}), 用于同内容去重与 Trace 完整性校验。
 *   <li>必须可以映射回真实 Document 与 Chunk (documentId / chunkId 必填)。
 *   <li>无权 Evidence <b>不</b> 允许进入后续阶段 —— 由 RetrieveService 在 AccessScope sentinel +
 *       MilvusFilterExprBuilder empty=ALWAYS_FALSE 处先行截断, 构造再多一层 tenantId 一致性断言。
 * </ul>
 *
 * <p>第一版 {@code sourceTool} 仅 {@code "retriever"} / {@code "reranker"} / {@code "context"} 三类, 与
 * RetrieveService 现有结构对齐; PR-4 Tool Contract 之后再扩展为 Tool 名清单。
 *
 * <p>{@code metadata} 为只读附加 (page / sectionPath / rerank state 等), 不参与 evidenceId hash (内容相同的 chunk
 * 不允许无意义 metadata 差异产生不同的 evidenceId)。
 */
public record Evidence(
        String evidenceId,
        String tenantId,
        Long documentId,
        Long chunkId,
        String documentVersion,
        String content,
        String contentHash,
        Double retrievalScore,
        Double rerankScore,
        String sourceTool,
        Map<String, Object> metadata) {

    /** 工厂: 服务端构造时强制注入 tenantId, 自动算 evidenceId / contentHash。 */
    public static Evidence of(
            String tenantId,
            Long documentId,
            Long chunkId,
            String documentVersion,
            String content,
            Double retrievalScore,
            Double rerankScore,
            String sourceTool,
            Map<String, Object> metadata) {
        if (tenantId == null || tenantId.isBlank()) {
            // 服务端不变量: tenantId 永远来自 Principal, 不接受 caller 传入空。
            throw new IllegalArgumentException("Evidence.tenantId 必须由服务端 Principal 注入");
        }
        if (documentId == null || chunkId == null) {
            throw new IllegalArgumentException("Evidence documentId / chunkId 必填");
        }
        String safeContent = content == null ? "" : content;
        String contentHash = sha256(safeContent);
        String evidenceId = sha256(tenantId + "|" + documentId + "|" + chunkId + "|" + contentHash);
        return new Evidence(
                evidenceId,
                tenantId,
                documentId,
                chunkId,
                documentVersion,
                safeContent,
                contentHash,
                retrievalScore,
                rerankScore,
                sourceTool,
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /**
     * sha256 → 64 位小写 hex (与项目现有 {@code ContentHash} 风格对齐)。
     *
     * <p>公开供 RetrieveService 等同模块做 contentHash 去重, 不强制每个 caller 自己再算一遍。
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
