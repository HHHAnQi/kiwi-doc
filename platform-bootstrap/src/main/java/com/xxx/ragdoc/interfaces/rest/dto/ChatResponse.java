package com.xxx.ragdoc.interfaces.rest.dto;

import com.xxx.ragdoc.application.chat.command.ChatResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * chat 接口响应 DTO。 契约见 docs/features/api-contracts.md §D1。
 *
 * <p>所有路径(成功 / 业务降级)统一用 200 + 此结构, body schema 单一, 客户端易处理。
 */
@Schema(name = "ChatResponse")
public record ChatResponse(
        @Schema(description = "答案正文(可能是真实答案或兜底文案)") String answer,
        @Schema(description = "引用列表(V1 永远为空数组)") List<Citation> citations,
        @Schema(description = "业务状态, OK/EMPTY_KB/NO_RECALL/LLM_DEGRADED", example = "EMPTY_KB")
                String stateHint,
        @Schema(description = "用于 feedback 反馈关联") String traceId) {
    public static ChatResponse from(ChatResult r) {
        List<Citation> citations =
                r.citations().stream()
                        .map(c -> new Citation(c.chunkId(), c.docId(), c.page(), c.snippet()))
                        .toList();
        return new ChatResponse(r.answer(), citations, r.stateHint().name(), r.traceId().value());
    }

    public record Citation(Long chunkId, Long docId, int page, String snippet) {}
}
