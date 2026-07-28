package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.domain.shared.TraceId;
import com.xxx.ragdoc.interfaces.rest.dto.ChatRequest;
import com.xxx.ragdoc.interfaces.rest.dto.ChatResponse;
import com.xxx.ragdoc.interfaces.rest.filter.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * chat REST 接口(V1 stub 版本)。
 *
 * <p>V1 行为:
 *
 * <ul>
 *   <li>0 文档 → 200 + EMPTY_KB
 *   <li>≥1 文档 → 200 + NO_RECALL(stub 无召回)
 *   <li>限定 doc 未 READY → 409 DOC_NOT_READY
 *   <li>每次都落 chat_traces
 * </ul>
 *
 * <p>trace_id 从 {@link TraceIdFilter} 写入的 MDC 取(由 Filter 统一注入响应头)。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "基于知识库的问答")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @Operation(
            summary = "同步问答(V1 stub)",
            description = "V1 仅返回 EMPTY_KB / NO_RECALL 兜底; V2 接入真实召回与 LLM 后支持 OK / LLM_DEGRADED")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        TraceId tid = new TraceId(traceId);

        ChatCommand cmd = new ChatCommand(request.query(), request.docId(), request.topK());
        ChatResult result = chatService.chat(cmd, tid);

        return ChatResponse.from(result);
    }
}
