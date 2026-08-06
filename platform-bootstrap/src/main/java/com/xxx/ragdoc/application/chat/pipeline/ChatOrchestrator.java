package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.RouterProperties;
import com.xxx.ragdoc.application.chat.router.TaskRouter;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class ChatOrchestrator {

    private final ChatPipelineRegistry registry;
    private final TraceObserver traceObserver;
    /** 可选: RouterProperties + RuleBasedTaskRouter bean (PR-3.2 引入); 关闭时 AUTO 仍回 Classic。 */
    private final RouterProperties routerProperties;
    private final TaskRouter taskRouter;

    @Autowired
    public ChatOrchestrator(
            ChatPipelineRegistry registry,
            TraceObserver traceObserver,
            RouterProperties routerProperties,
            TaskRouter taskRouter) {
        this.registry = registry;
        this.traceObserver = traceObserver;
        this.routerProperties = routerProperties;
        this.taskRouter = taskRouter;
    }

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

    /**
     * 决策结果: 除最终 {@link PipelineType}, 还带上 {@link RouterDecision} 用于 Trace / 日志。
     *
     * <p>当 {@code rag.router.enabled=false} 或 mode=RAG 时, RouterDecision 用占位 (intent=FACT,
     * strategy=CLASSIC_RAG, reasonCode=ROUTER_DISABLED), 仅用于统一 Trace 字段。
     */
    private record Routed(PipelineType pipelineType, RouterDecision decision) {}

    /**
     * PR-3 路由 (Agentic 仍按 PR-2 阻塞):
     *
     * <ul>
     *   <li>AGENTIC → 抛 {@link ErrorCode#AGENTIC_MODE_UNAVAILABLE}
     *   <li>RAG → 直接 CLASSIC_RAG (硬保留, 不经 Router)
     *   <li>AUTO + rag.router.enabled=false → CLASSIC_RAG (PR-2 行为)
     *   <li>AUTO + rag.router.enabled=true → TaskRouter.route(), strategy→PipelineType
     * </ul>
     *
     * <p>PR-3.3/3.4 之前, Router 可能输出 {@link ExecutionStrategy#TARGETED_RAG} /
     * {@link ExecutionStrategy#FIXED_WORKFLOW}, 但对应的 Pipeline 未注册 → Registry 抛
     * {@link ErrorCode#PIPELINE_NOT_FOUND} (HTTP 500, fail-closed) — 这就是需 {@code rag.router.enabled}
     * 默认 false 的原因: 在 PR-3.4 完成前避免 AUTO 真切到那些未实现的 strategy。
     *
     * <p>REFUSE strategy 当前不直接走 pipeline; 由 ClassicRagPipeline 既有 EMPTY_KB/NO_RECALL 路径
     * 兜底(Classic 检索会自然返回 NO_RECALL),保持单一终态契约不动。PR-3.4 后再单独有 RefusalPipeline。
     */
    private Routed route(ChatCommand command, ChatMode mode) {
        ChatMode safeMode = mode == null ? ChatMode.AUTO : mode;
        switch (safeMode) {
            case AGENTIC -> throw new DomainException(
                    ErrorCode.AGENTIC_MODE_UNAVAILABLE,
                    "Agent 模式在当前环境未启用 (PR-2 仅实现 Classic RAG); 请使用 RAG 或 AUTO 模式。");
            case RAG -> {
                // RAG 模式硬保留 Classic, 不经过 Router (EMSPR3 强约束: RAG 必须 Classic RAG)
                return new Routed(
                        PipelineType.CLASSIC_RAG,
                        new RouterDecision(
                                com.xxx.ragdoc.application.chat.router.TaskIntent.FACT,
                                ExecutionStrategy.CLASSIC_RAG,
                                java.util.List.of(),
                                java.util.Map.of(),
                                1.0,
                                "RAG_MODE_FORCED"));
            }
            case AUTO -> {
                if (!routerProperties.isEnabled()) {
                    return new Routed(
                            PipelineType.CLASSIC_RAG,
                            new RouterDecision(
                                    com.xxx.ragdoc.application.chat.router.TaskIntent.FACT,
                                    ExecutionStrategy.CLASSIC_RAG,
                                    java.util.List.of(),
                                    java.util.Map.of(),
                                    1.0,
                                    "ROUTER_DISABLED"));
                }
                RouterDecision d = routeWithFallback(command);
                return new Routed(toPipelineType(d.strategy()), d);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Router 失败时 fail-closed 到 CLASSIC_RAG (与 Router 内部低置信回退语义一致),不丢请求。 */
    private RouterDecision routeWithFallback(ChatCommand command) {
        try {
            RouterDecision d = taskRouter.route(command.query());
            return d;
        } catch (RuntimeException ex) {
            log.warn(
                    "orchestrator.router_failed query='{}', fallback_to_classic err={}",
                    command.query(),
                    ex.getMessage());
            return new RouterDecision(
                    com.xxx.ragdoc.application.chat.router.TaskIntent.FACT,
                    ExecutionStrategy.CLASSIC_RAG,
                    java.util.List.of(),
                    java.util.Map.of(),
                    0.0,
                    "ROUTER_EXCEPTION_FALLBACK");
        }
    }

    /** ExecutionStrategy → PipelineType: CLASSIC_RAG/TARGETED_RAG/FIXED_WORKFLOW 直接映射; REFUSE→CLASSIC+Classic 兜底。 */
    private static PipelineType toPipelineType(ExecutionStrategy strategy) {
        return switch (strategy) {
            case CLASSIC_RAG -> PipelineType.CLASSIC_RAG;
            case TARGETED_RAG -> PipelineType.TARGETED_RAG;
            case FIXED_WORKFLOW -> PipelineType.FIXED_WORKFLOW;
            case REFUSE -> PipelineType.CLASSIC_RAG; // PR-3.4 前由 Classic 检索兜底(NO_RECALL)
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
        Routed routed = route(command, safeMode);
        PipelineType effective = routed.pipelineType();
        RouterDecision decision = routed.decision();
        String requestId = generateRequestId(traceId);
        ChatExecutionContext ctx =
                new ChatExecutionContext(
                        requestId, principal, safeMode, effective, traceId, ExecutionPolicy.defaults());
        // PR-3: 把 router_decision/intent/entities/confidence/reasonCode 进 MDC + Trace,
        // 让一次请求可在 Langfuse / 日志中按 reasonCode / intent 过滤 (不影响业务路径)。
        try {
            org.slf4j.MDC.put(ORCH_MDC_REQUESTED_MODE, ctx.requestedMode().name());
            org.slf4j.MDC.put(ORCH_MDC_EFFECTIVE_PIPELINE, ctx.effectivePipeline().name());
            org.slf4j.MDC.put(ORCH_MDC_REQUEST_ID, ctx.requestId());
            org.slf4j.MDC.put(ORCH_MDC_ROUTER_INTENT, decision.intent().name());
            org.slf4j.MDC.put(ORCH_MDC_ROUTER_REASON, decision.reasonCode());
        } catch (Exception ignore) {
            // MDC 不可用时降级, 不阻塞业务
        }
        recordRouterDecision(ctx.traceId().value(), decision);
        return ctx;
    }

    /** PR-2: MDC keys, 下游可在 trace 时补 metadata; 公开供测试断言。 */
    public static final String ORCH_MDC_REQUESTED_MODE = "orch.requested_mode";
    public static final String ORCH_MDC_EFFECTIVE_PIPELINE = "orch.effective_pipeline";
    public static final String ORCH_MDC_REQUEST_ID = "orch.request_id";
    /** PR-3: Router 字段也入 MDC, 便于日志按 intent / reasonCode 过滤。 */
    public static final String ORCH_MDC_ROUTER_INTENT = "orch.router_intent";
    public static final String ORCH_MDC_ROUTER_REASON = "orch.router_reason";

    private static String generateRequestId(TraceId traceId) {
        // 用 trace_id 短前缀 + 时间戳作为 requestId; 便于日志关联又不与 trace_id 完全重复。
        // 时间戳用 System.nanoTime 在 PR-2 内不参与 hash(为关联日志方便直接展示), 不进入业务路径。
        String prefix = traceId.value().length() > 8 ? traceId.value().substring(0, 8) : traceId.value();
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36);
    }

    /** PR-3: 把 RouterDecision 入 Trace observation metadata (Langfuse/NoOp 兼容, 失败不阻塞)。 */
    private void recordRouterDecision(String traceId, RouterDecision decision) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("router_intent", decision.intent().name());
            meta.put("router_strategy", decision.strategy().name());
            meta.put("router_confidence", decision.confidence());
            meta.put("router_reason_code", decision.reasonCode());
            meta.put("router_entities", decision.entities());
            meta.put("router_filters_keys", decision.filters().keySet());
            traceObserver.observe(
                    traceId,
                    TraceObserver.ObservationType.DECISION,
                    "router.decision",
                    null,
                    null,
                    0,
                    meta);
        } catch (Exception ignore) {
            // trace 路径失败不阻塞业务;observe 接口契约保证 NoOp 路径不出异常
        }
    }

    private void logOrchestratorStart(ChatExecutionContext ctx) {        log.info(
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
