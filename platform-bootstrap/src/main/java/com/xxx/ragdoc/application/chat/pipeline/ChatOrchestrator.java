package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.TraceId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * PR-2 / EMS-PR2: 统一 chat 执行入口。
 *
 * <p>Controller 只调用本类。Orchestrator 负责:
 *
 * <ol>
 *   <li>从 {@link AuthContext} 取已认证 {@link Principal} (不接受客户端传 tenantId / userId)
 *   <li>解析 requested mode 并路由到 effective {@link PipelineType}
 *   <li>从 {@link ChatPipelineRegistry} 取 pipeline, fail-closed 缺失
 *   <li>构造不可变 {@link ChatExecutionContext}, 保持 request_id / Principal / Trace 在并发间不串线
 *   <li>调度 pipeline 的 {@code execute}/{@code stream}
 *   <li>把 requested mode + effective pipeline 写入 Trace 与日志
 * </ol>
 *
 * <h2>PR-2 路由规则 (硬约束)</h2>
 *
 * <ul>
 *   <li>{@link ChatMode#RAG} / {@link ChatMode#AUTO} → {@link PipelineType#CLASSIC_RAG}
 *   <li>{@link ChatMode#AGENTIC} → 直接抛 {@link ErrorCode#AGENTIC_MODE_UNAVAILABLE} (HTTP 422),
 *       <b>不</b> 调用任何 pipeline, 不静默回退 Classic, 不写 success Trace
 * </ul>
 *
 * <p>未实现的 Router (PR-3) 不能在 PR-2 中拼出来; {@code AUTO} 暂时与 {@code RAG} 等价走 Classic。
 *
 * <h2>Trace 字段 (PR-2 新增)</h2>
 *
 * <p>每次请求在 {@link TraceObserver#startTrace} 的 metadata 里追加:
 *
 * <ul>
 *   <li>{@code requested_chat_mode} — 用户原始 mode
 *   <li>{@code effective_pipeline} — Orchestrator 实际派发的 type
 *   <li>{@code request_id} — Orchestrator 自己生成 (与 trace_id 分离; 短请求 ID 便于关联日志/Trace)
 *   <li>{@code tenant_id} / {@code user_id} — 从 Principal 派生
 * </ul>
 *
 * <p>不记录原始 token, 不记录无权 Evidence; {@code AGENTIC_MODE_UNAVAILABLE} 经 GlobalExceptionHandler
 * 走失败路径, 不在本 Orchestrator 里落 OK Trace。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private final ChatPipelineRegistry registry;

    /**
     * 同步入口。
     *
     * @param command 已校验的业务输入 (含 query / 过滤 / conversationId)
     * @param traceId 来自 {@code TraceIdFilter} 注入 MDC 的 trace_id, 贯穿 Trace
     * @param requestedMode 可空 (= {@link ChatMode#AUTO}, 老客户端兼容)
     */
    public ChatResult execute(ChatCommand command, TraceId traceId, ChatMode requestedMode) {
        ChatExecutionContext ctx = newContext(command, traceId, requestedMode);
        // AGENTIC 路由在 newContext 内抛 → 走 GlobalExceptionHandler 422, 不调用任何 pipeline
        logOrchestratorStart(ctx);
        ChatPipeline pipeline = resolveAndVerifyPipeline(ctx);
        try {
            return pipeline.execute(command, ctx);
        } catch (DomainException de) {
            logOrchestratorFailure(ctx, de.errorCode().code(), de.getMessage());
            throw de;
        } catch (RuntimeException ex) {
            logOrchestratorFailure(ctx, "SYS_INTERNAL", ex.getMessage());
            throw ex;
        }
    }

    /**
     * 流式入口。
     *
     * <p>AGENTIC 失败在订阅前就抛 — 让 Controller 走标准 exception handler (HTTP 422),
     * 而不是假装在流里发 done/event; 这避免给 SSE 单终态不变量增加新分支。
     */
    public Flux<ChatStreamEvent> stream(ChatCommand command, TraceId traceId, ChatMode requestedMode) {
        ChatExecutionContext ctx = newContext(command, traceId, requestedMode);
        logOrchestratorStart(ctx);
        ChatPipeline pipeline = resolveAndVerifyPipeline(ctx);
        return pipeline.stream(command, ctx);
    }

    // ─── 内部 ────────────────────────────────────────────────

    /** 把 ChatMode 映射到 PipelineType; AGENTIC → 抛 DomainException 直接拒绝。 */
    private PipelineType route(ChatMode mode) {
        ChatMode effective = mode == null ? ChatMode.AUTO : mode;
        return switch (effective) {
            case RAG, AUTO -> PipelineType.CLASSIC_RAG;
            case AGENTIC -> throw new DomainException(
                    ErrorCode.AGENTIC_MODE_UNAVAILABLE,
                    "Agent 模式在当前环境未启用 (PR-2 仅实现 Classic RAG); 请使用 RAG 或 AUTO 模式。");
        };
    }

    /** 解析 pipeline + 二次校验 effectivePipeline 与 chat mode 一致性。 */
    private ChatPipeline resolveAndVerifyPipeline(ChatExecutionContext ctx) {
        ChatPipeline pipeline = registry.get(ctx.effectivePipeline());
        log.info(
                "orchestrator.dispatch request_id={}, trace_id={}, mode={}, pipeline={}, impl={}",
                ctx.requestId(),
                ctx.traceId().value(),
                ctx.requestedMode(),
                ctx.effectivePipeline(),
                pipeline.getClass().getSimpleName());
        return pipeline;
    }

    private ChatExecutionContext newContext(ChatCommand command, TraceId traceId, ChatMode mode) {
        Principal principal = AuthContext.currentPrincipal();
        ChatMode safeMode = mode == null ? ChatMode.AUTO : mode;
        PipelineType effective = route(safeMode);
        String requestId = generateRequestId(traceId);
        ChatExecutionContext ctx =
                new ChatExecutionContext(
                        requestId, principal, safeMode, effective, traceId, ExecutionPolicy.defaults());
        // PR-2: requested mode / effective pipeline / request_id 进 MDC,
        // 让日志与下游 ChatService.startTrace 的 metadata 在需要时能读到一致字段,
        // 而不与 Langfuse 的 lfTrace channel 冲突(避免双 trace_id)。
        try {
            org.slf4j.MDC.put(ORCH_MDC_REQUESTED_MODE, ctx.requestedMode().name());
            org.slf4j.MDC.put(ORCH_MDC_EFFECTIVE_PIPELINE, ctx.effectivePipeline().name());
            org.slf4j.MDC.put(ORCH_MDC_REQUEST_ID, ctx.requestId());
        } catch (Exception ignore) {
            // MDC 不可用时降级, 不阻塞业务
        }
        return ctx;
    }

    /** PR-2: MDC keys, 下游可在 trace 时补 metadata; 公开供测试断言。 */
    public static final String ORCH_MDC_REQUESTED_MODE = "orch.requested_mode";
    public static final String ORCH_MDC_EFFECTIVE_PIPELINE = "orch.effective_pipeline";
    public static final String ORCH_MDC_REQUEST_ID = "orch.request_id";

    private static String generateRequestId(TraceId traceId) {
        // 用 trace_id 短前缀 + 时间戳作为 requestId; 便于日志关联又不与 trace_id 完全重复。
        // 时间戳用 System.nanoTime 在 PR-2 内不参与 hash(为关联日志方便直接展示), 不进入业务路径。
        String prefix = traceId.value().length() > 8 ? traceId.value().substring(0, 8) : traceId.value();
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36);
    }

    private void logOrchestratorStart(ChatExecutionContext ctx) {
        log.info(
                "orchestrator.start request_id={}, trace_id={}, requested_mode={}, effective_pipeline={}, tenant={}, user={}",
                ctx.requestId(),
                ctx.traceId().value(),
                ctx.requestedMode().name(),
                ctx.effectivePipeline().name(),
                ctx.principal().tenantId(),
                ctx.principal().userId());
    }

    private void logOrchestratorFailure(ChatExecutionContext ctx, String code, String message) {
        log.warn(
                "orchestrator.failure request_id={}, trace_id={}, code={}, msg={}",
                ctx.requestId(),
                ctx.traceId().value(),
                code,
                message == null ? "" : message);
    }
}
