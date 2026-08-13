package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.OnlineReasonCode;
import com.xxx.ragdoc.application.chat.router.OnlineRoute;
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
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 在线 chat 统一执行入口。同步与 SSE 只保留传输差异，路由、拒答和 pipeline 派发均经过
 * {@link OnlineExecutionKernel}。
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
 * <h2>路由规则 (硬约束)</h2>
 *
 * <ul>
 *   <li>{@link ChatMode#RAG} → {@link PipelineType#CLASSIC_RAG}
 *   <li>{@link ChatMode#AUTO} → CHAT / RETRIEVE / TOOL / REFUSE 四分流
 *   <li>{@link ChatMode#AGENTIC} → 仅在 Planner 与 Planned Pipeline 双开关均启用时直达
 *       {@link PipelineType#PLANNED_AGENT}; 否则抛 {@link ErrorCode#AGENTIC_MODE_UNAVAILABLE}
 * </ul>
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
 * <p>不记录原始 token, 不记录无权 Evidence; {@code AGENTIC_MODE_UNAVAILABLE} 经 GlobalExceptionHandler 走失败路径,
 * 不在本 Orchestrator 里落 OK Trace。
 */
@Slf4j
@Service
public class ChatOrchestrator {

    private final ChatPipelineRegistry registry;
    private final TraceObserver traceObserver;

    /** Router 紧急回滚开关；关闭时 AUTO 回到 Classic。 */
    private final RouterProperties routerProperties;

    private final TaskRouter taskRouter;

    /** PR-7c.3c: 能力解析器 — MULTI_HOP + flags + confidence → PLANNED_AGENT; 默认全 false 保持零回归。 */
    private final com.xxx.ragdoc.application.chat.planned.ExecutionStrategyResolver
            strategyResolver;
    private final OnlineExecutionKernel executionKernel;
    private final OnlineExecutionProperties executionProperties;

    @Autowired
    public ChatOrchestrator(
            ChatPipelineRegistry registry,
            TraceObserver traceObserver,
            RouterProperties routerProperties,
            TaskRouter taskRouter,
            com.xxx.ragdoc.application.chat.planned.ExecutionStrategyResolver strategyResolver,
            OnlineExecutionKernel executionKernel,
            OnlineExecutionProperties executionProperties) {
        this.registry = registry;
        this.traceObserver = traceObserver;
        this.routerProperties = routerProperties;
        this.taskRouter = taskRouter;
        this.strategyResolver = strategyResolver;
        this.executionKernel = executionKernel;
        this.executionProperties = executionProperties;
    }

    /** 单元测试和旧装配兼容构造。 */
    public ChatOrchestrator(
            ChatPipelineRegistry registry,
            TraceObserver traceObserver,
            RouterProperties routerProperties,
            TaskRouter taskRouter,
            com.xxx.ragdoc.application.chat.planned.ExecutionStrategyResolver strategyResolver) {
        this(registry, traceObserver, routerProperties, taskRouter, strategyResolver,
                new OnlineExecutionKernel(registry), new OnlineExecutionProperties());
    }

    /**
     * 同步入口。
     *
     * @param command 已校验的业务输入 (含 query / 过滤 / conversationId)
     * @param traceId 来自 {@code TraceIdFilter} 注入 MDC 的 trace_id, 贯穿 Trace
     * @param requestedMode 可空 (= {@link ChatMode#AUTO}, 老客户端兼容)
     */
    public ChatResult execute(ChatCommand command, TraceId traceId, ChatMode requestedMode) {
        OnlineExecutionContext ctx = newContext(command, traceId, requestedMode);
        // AGENTIC 路由在 newContext 内抛 → 走 GlobalExceptionHandler 422, 不调用任何 pipeline
        logOrchestratorStart(ctx);
        try {
            return executionKernel.execute(command, ctx);
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
     * <p>AGENTIC 失败在订阅前就抛 — 让 Controller 走标准 exception handler (HTTP 422), 而不是假装在流里发 done/event;
     * 这避免给 SSE 单终态不变量增加新分支。
     */
    public Flux<ChatStreamEvent> stream(
            ChatCommand command, TraceId traceId, ChatMode requestedMode) {
        OnlineExecutionContext ctx = newContext(command, traceId, requestedMode);
        logOrchestratorStart(ctx);
        return executionKernel.stream(command, ctx);
    }

    // ─── 内部 ────────────────────────────────────────────────

    /**
     * 决策结果: 除最终 {@link PipelineType}, 还带上 {@link RouterDecision} 用于 Trace / 日志。
     *
     * <p>当 {@code rag.router.enabled=false} 或 mode=RAG 时, RouterDecision 用占位 (intent=FACT,
     * strategy=CLASSIC_RAG, reasonCode=ROUTER_DISABLED), 仅用于统一 Trace 字段。
     */
    private record Routed(
            OnlineRoute route,
            PipelineType pipelineType,
            RouterDecision decision,
            OnlineReasonCode reasonCode) {}

    /**
     * 统一路由: RAG 强制 Classic, AUTO 由 Router 决策, AGENTIC 由能力开关显式门禁。
     *
     * <ul>
     *   <li>AGENTIC → 抛 {@link ErrorCode#AGENTIC_MODE_UNAVAILABLE}
     *   <li>RAG → 直接 CLASSIC_RAG (硬保留, 不经 Router)
     *   <li>AUTO + rag.router.enabled=false → CLASSIC_RAG (PR-2 行为)
     *   <li>AUTO + rag.router.enabled=true → TaskRouter.route(), strategy→PipelineType
     * </ul>
     *
     * <p>REFUSE 是无 pipeline 的终态，禁止通过异常处理或兼容降级回退到 Classic RAG。
     */
    private Routed route(ChatCommand command, ChatMode mode) {
        ChatMode safeMode = mode == null ? ChatMode.AUTO : mode;
        switch (safeMode) {
            case AGENTIC -> {
                if (!strategyResolver.isAgenticModeAvailable()) {
                    throw new DomainException(
                            ErrorCode.AGENTIC_MODE_UNAVAILABLE,
                            "Agentic RAG 当前未启用; 需同时开启 planner.enabled 与 planned-pipeline-enabled。");
                }
                // 显式 AGENTIC 是调用方的执行策略选择，不再经过意图分类器；仍复用同一套
                // Planner/Tool/Evidence/Sufficiency/Budget 安全边界。
                return new Routed(
                        OnlineRoute.RETRIEVE,
                        PipelineType.PLANNED_AGENT,
                        new RouterDecision(
                                com.xxx.ragdoc.application.chat.router.TaskIntent.MULTI_HOP,
                                ExecutionStrategy.PLANNED_AGENT,
                                java.util.List.of(),
                                java.util.Map.of(),
                                1.0,
                                "AGENTIC_MODE_FORCED"),
                        OnlineReasonCode.AGENTIC_MODE_FORCED);
            }
            case RAG -> {
                // RAG 模式硬保留 Classic, 不经过 Router (EMSPR3 强约束: RAG 必须 Classic RAG)
                return new Routed(
                        OnlineRoute.RETRIEVE,
                        PipelineType.CLASSIC_RAG,
                        new RouterDecision(
                                com.xxx.ragdoc.application.chat.router.TaskIntent.FACT,
                                ExecutionStrategy.CLASSIC_RAG,
                                java.util.List.of(),
                                java.util.Map.of(),
                                1.0,
                                "RAG_MODE_FORCED"),
                        OnlineReasonCode.RAG_MODE_FORCED);
            }
            case AUTO -> {
                if (!routerProperties.isEnabled()) {
                    return new Routed(
                            OnlineRoute.RETRIEVE,
                            PipelineType.CLASSIC_RAG,
                            new RouterDecision(
                                    com.xxx.ragdoc.application.chat.router.TaskIntent.FACT,
                                    ExecutionStrategy.CLASSIC_RAG,
                                    java.util.List.of(),
                                    java.util.Map.of(),
                                    1.0,
                                    "ROUTER_DISABLED"),
                            OnlineReasonCode.ROUTER_DISABLED);
                }
                RouterDecision d = routeWithFallback(command);
                // PR-7c.3c: 能力门禁 (Planner Flag + confidence + MULTI_HOP → PLANNED_AGENT); 默认全 false
                // 时 zero-diff
                ExecutionStrategy resolved = strategyResolver.resolve(d, d.strategy());
                RouterDecision resolvedD = withStrategy(d, resolved);
                OnlineRoute onlineRoute = toOnlineRoute(resolved);
                PipelineType pipeline = onlineRoute == OnlineRoute.REFUSE ? null : toPipelineType(resolved);
                return new Routed(onlineRoute, pipeline, resolvedD, OnlineReasonCode.from(resolvedD));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Router 技术异常时降级到 CLASSIC_RAG；安全策略命中的 REFUSE 不走此异常分支。 */
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

    /**
     * ExecutionStrategy → PipelineType。REFUSE 不存在映射，调用即为编程错误。
     */
    private static PipelineType toPipelineType(ExecutionStrategy strategy) {
        return switch (strategy) {
            case DIRECT_CHAT -> PipelineType.DIRECT_CHAT;
            case CLASSIC_RAG -> PipelineType.CLASSIC_RAG;
            case TARGETED_RAG -> PipelineType.TARGETED_RAG;
            case FIXED_WORKFLOW -> PipelineType.FIXED_WORKFLOW;
            case PLANNED_AGENT -> PipelineType.PLANNED_AGENT;
            case TOOL_EXECUTION -> PipelineType.TOOL_EXECUTION;
            case REFUSE -> throw new IllegalArgumentException("REFUSE 不允许映射到 pipeline");
        };
    }

    private static OnlineRoute toOnlineRoute(ExecutionStrategy strategy) {
        return switch (strategy) {
            case DIRECT_CHAT -> OnlineRoute.CHAT;
            case TOOL_EXECUTION -> OnlineRoute.TOOL;
            case REFUSE -> OnlineRoute.REFUSE;
            default -> OnlineRoute.RETRIEVE;
        };
    }

    /** PR-7c.3c: RouterDecision 不可变 record; 用同样字段重建带新 strategy 的副本。 */
    private static RouterDecision withStrategy(RouterDecision d, ExecutionStrategy newStrategy) {
        if (d == null || newStrategy == d.strategy()) return d;
        return new RouterDecision(
                d.intent(), newStrategy, d.entities(), d.filters(), d.confidence(), d.reasonCode());
    }

    private OnlineExecutionContext newContext(ChatCommand command, TraceId traceId, ChatMode mode) {
        Principal principal = AuthContext.currentPrincipal();
        ChatMode safeMode = mode == null ? ChatMode.AUTO : mode;
        Routed routed = route(command, safeMode);
        PipelineType effective = routed.pipelineType();
        RouterDecision decision = routed.decision();
        String requestId = generateRequestId(traceId);
        OnlineExecutionContext ctx =
                new OnlineExecutionContext(
                        requestId,
                        principal,
                        safeMode,
                        routed.route(),
                        effective,
                        traceId,
                        new ExecutionPolicy(true, executionProperties.getTimeoutMs(), 0L),
                        decision,
                        routed.reasonCode(),
                        executionProperties.getContextTokenBudget(),
                        Instant.now().plusMillis(executionProperties.getTimeoutMs()));
        // PR-3: 把 router_decision/intent/entities/confidence/reasonCode 进 MDC + Trace,
        // 让一次请求可在 Langfuse / 日志中按 reasonCode / intent 过滤 (不影响业务路径)。
        try {
            org.slf4j.MDC.put(ORCH_MDC_REQUESTED_MODE, ctx.requestedMode().name());
            org.slf4j.MDC.put(
                    ORCH_MDC_EFFECTIVE_PIPELINE,
                    ctx.effectivePipeline() == null ? "NONE" : ctx.effectivePipeline().name());
            org.slf4j.MDC.put(ORCH_MDC_REQUEST_ID, ctx.requestId());
            org.slf4j.MDC.put(ORCH_MDC_ROUTER_INTENT, decision.intent().name());
            org.slf4j.MDC.put(ORCH_MDC_ROUTER_REASON, decision.reasonCode());
            org.slf4j.MDC.put(ORCH_MDC_REASON_CODE, ctx.reasonCode().name());
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
    public static final String ORCH_MDC_REASON_CODE = "orch.reason_code";

    private static String generateRequestId(TraceId traceId) {
        // 用 trace_id 短前缀 + 时间戳作为 requestId; 便于日志关联又不与 trace_id 完全重复。
        // 时间戳用 System.nanoTime 在 PR-2 内不参与 hash(为关联日志方便直接展示), 不进入业务路径。
        String prefix =
                traceId.value().length() > 8 ? traceId.value().substring(0, 8) : traceId.value();
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

    private void logOrchestratorStart(OnlineExecutionContext ctx) {
        log.info(
                "orchestrator.start request_id={}, trace_id={}, requested_mode={}, route={}, effective_pipeline={}, reason_code={}, tenant={}, user={}",
                ctx.requestId(),
                ctx.traceId().value(),
                ctx.requestedMode().name(),
                ctx.route().name(),
                ctx.effectivePipeline() == null ? "NONE" : ctx.effectivePipeline().name(),
                ctx.reasonCode().name(),
                ctx.principal().tenantId(),
                ctx.principal().userId());
    }

    private void logOrchestratorFailure(OnlineExecutionContext ctx, String code, String message) {
        log.warn(
                "orchestrator.failure request_id={}, trace_id={}, code={}, msg={}",
                ctx.requestId(),
                ctx.traceId().value(),
                code,
                message == null ? "" : message);
    }
}
