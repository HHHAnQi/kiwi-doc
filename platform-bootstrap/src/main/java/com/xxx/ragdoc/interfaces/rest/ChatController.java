package com.xxx.ragdoc.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.EvidenceDebugProperties;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.pipeline.ChatOrchestrator;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    // PR-2 / EMS-PR2: Controller 不再直依赖 ChatService; 统一走 ChatOrchestrator
    // (同步与 SSE 均经过 Orchestrator → Registry → ClassicRagPipeline → ChatService)
    private final ChatOrchestrator chatOrchestrator;
    private final EvidenceDebugProperties evidenceDebugProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    @Operation(
            summary = "同步问答(V1 stub)",
            description =
                    "V1 仅返回 EMPTY_KB / NO_RECALL 兜底; V2 接入真实召回与 LLM "
                            + "后支持 OK / LLM_DEGRADED; V3-W1 加 /chat/sse 流式版本")
    public org.springframework.http.ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "X-Debug-Evidence",
                            required = false)
                    String debugEvidenceHeader) {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        TraceId tid = new TraceId(traceId);

        ChatCommand cmd =
                new ChatCommand(
                        request.query(),
                        request.docId(),
                        request.topK(),
                        request.source(),
                        request.version(),
                        request.language(),
                        request.conversationId());
        // PR-2: 经 Orchestrator 路由; mode=null → AUTO(默认); AGENTIC 在 Orchestrator 内抛 422
        ChatResult result = chatOrchestrator.execute(cmd, tid, request.mode());

        // PR-1 / EMS-PR1: 仅当服务端 rag.evidence.debug-enabled=true 且请求显式带
        // X-Debug-Evidence: true 时, 响应才包含真实 Evidence 快照。其它一律走安全 Citation 路径。
        boolean includeEvidence =
                evidenceDebugProperties.isDebugEnabled()
                        && "true".equalsIgnoreCase(debugEvidenceHeader);
        // G2 可测性: 透出 rewrite 后的实际检索 query(仅当与原 query 不同时存在)
        String effectiveQuery = org.slf4j.MDC.get("rag.effectiveQuery");
        var builder =
                org.springframework.http.ResponseEntity.ok()
                        .body(ChatResponse.from(result, includeEvidence));
        // Agent 过程可视化: AGENTIC 路径透出 runId(ASCII, 无需编码)
        String agentRunId = org.slf4j.MDC.get("rag.agentRunId");
        if (agentRunId != null) {
            builder = org.springframework.http.ResponseEntity.ok()
                    .header("X-Agent-Run-Id", agentRunId)
                    .body(ChatResponse.from(result, includeEvidence));
            org.slf4j.MDC.remove("rag.agentRunId");
        }
        if (effectiveQuery != null) {
            // HTTP 头只允许 ISO-8859-1, 中文会被 Spring 静默丢弃(实测) → URL 编码传输
            builder =
                    org.springframework.http.ResponseEntity.ok()
                            .header(
                                    "X-Effective-Query",
                                    java.net.URLEncoder.encode(
                                            effectiveQuery, java.nio.charset.StandardCharsets.UTF_8))
                            .body(ChatResponse.from(result, includeEvidence));
            org.slf4j.MDC.remove("rag.effectiveQuery");
        }
        return builder;
    }

    /**
     * V3 W1: SSE 流式问答。
     *
     * <p>响应 Content-Type: text/event-stream, 每条事件 3 个字段:
     *
     * <pre>
     * event: citations
     * data: {"citations":[{"chunkId":123,...}]}
     *
     * event: delta
     * data: {"delta":"Sentinel"}
     *
     * event: delta
     * data: {"delta":" 用 SphU.entry 限流"}
     *
     * event: done
     * data: {"traceId":"abc","stateHint":"OK"}
     * </pre>
     *
     * <p>体感收益: 首 token &lt; 1.5s(对齐 ADR-0004 L3 SLA) — 用户不再等 8-15s 看全文才出。
     *
     * <p>错误: LLM 失败时 ChatService 转发{@link ChatStreamEvent.DoneEvent} state=LLM_DEGRADED, 而不是抛异常,
     * 让前端统一在 onComplete 收尾。
     */
    @PostMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "流式 SSE 问答(V3 W1 新增)",
            description =
                    "Server-Sent Events 协议, 先发 citations → 多个 delta token → done。"
                            + " 首 token <1.5s 让用户立刻看到答案增量。")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_KEY);
        TraceId tid = new TraceId(traceId);

        // 5 分钟 timeout(对应 LLM_TIMEOUT_MS 上限 + buffer)
        SseEmitter emitter = new SseEmitter(300_000L);

        ChatCommand cmd =
                new ChatCommand(
                        request.query(),
                        request.docId(),
                        request.topK(),
                        request.source(),
                        request.version(),
                        request.language(),
                        request.conversationId());

        // PR-2: SSE 经 Orchestrator 路由; AGENTIC 在订阅前抛 → GlobalExceptionHandler 转 422,
        // 不进入 SSE 单终态契约 (避免在没有流出的情况下产生额外终态事件)。
        chatOrchestrator.stream(cmd, tid, request.mode())
                .subscribe(
                        event -> {
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name(event.type())
                                                .data(objectMapper.writeValueAsString(event)));
                            } catch (Exception e) {
                                log.warn(
                                        "chat.sse_send_failed trace_id={}, event_type={}, err={}",
                                        tid.value(),
                                        event.type(),
                                        e.getMessage());
                            }
                        },
                        error -> {
                            // 兜底: subscribe 异常(理论上 ChatService 已 onErrorResume 处理)
                            log.error("chat.sse_unhandled_error trace_id={}", tid.value(), error);
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name("error")
                                                .data(
                                                        "{\"traceId\":\""
                                                                + tid.value()
                                                                + "\",\"message\":\""
                                                                + (error.getMessage() == null
                                                                        ? "unknown"
                                                                        : error.getMessage())
                                                                + "\"}"));
                            } catch (Exception ignored) {
                                // ignore
                            } finally {
                                emitter.completeWithError(error);
                            }
                        },
                        emitter::complete);

        return emitter;
    }
}
