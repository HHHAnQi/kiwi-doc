package com.xxx.ragdoc.application.chat.harness;

import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskRouter;
import com.xxx.ragdoc.domain.auth.Principal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-5 / EMS-PR5: Router 的 Harness 包装。
 *
 * <p>LIVE / disabled — 直接调真实 Router, 不读不写 fixture, 等同 PR-3 行为。
 *
 * <p>RECORD — 调真实 Router 并记录 RouterDecision
 *
 * <p>REPLAY — 不调 Router, 直接读 fixture
 *
 * <p>每个 (caseId, runId) 维护独立 callIndex, 让单 Run 内 Router 多次调用可分别回放。
 */
@Slf4j
@RequiredArgsConstructor
public class HarnessAwareTaskRouter implements TaskRouter {

    private final TaskRouter delegate;
    private final HarnessProvider provider;
    private final HarnessProperties props;
    private final RouterHarnessAdapter adapter;

    /**
     * runId → callIndex counter。生产 disabled (LIVE) 时不构造 Invocation, counter 也不增。 测试 / Harness 启用时,
     * 由 caller 通过 {@link #startRun(String, String, Principal, String, String, String)} 显式 beginRun。
     */
    private final Map<String, AtomicInteger> callIndexByRun =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ThreadLocal<String> currentRun = new ThreadLocal<>();

    /** 调用方在 ChatOrchestrator / Agent executor 启动 run 时调。 */
    public void beginRun(
            String runId,
            String caseId,
            Principal principal,
            String permissionScopeVersion,
            String indexVersion,
            String traceId) {
        if (!props.isEnabled() || props.getMode() == HarnessMode.LIVE) return; // LIVE 无需开销
        currentRun.set(runId);
        callIndexByRun.put(runId, new AtomicInteger(0));
        // Principal 不进 InvocationContext; 只存脱敏 userIdHash (来自 caller 已算的)
        // 为简化, harness 默认 trace 也用 traceId 直接存 (不脱敏 → 后续可加 hash)
        // (调用方 PR-5 不切用此入口; 测试用)
    }

    public void endRun(String runId) {
        currentRun.remove();
        callIndexByRun.remove(runId);
    }

    @Override
    public RouterDecision route(String query) {
        if (!props.isEnabled() || props.getMode() == HarnessMode.LIVE) {
            // LIVE: 直接 delegate 不经 Harness (零开销)
            return delegate.route(query);
        }

        String runId = currentRun.get();
        if (runId == null) {
            // 缺 beginRun: 降级 LIVE, log warn (而非静默; 让测试尽快暴露 setup 缺失)
            log.warn(
                    "harness.router.no_active_run name={} mode={} — fallback LIVE",
                    delegate.getClass().getSimpleName(),
                    props.getMode());
            return delegate.route(query);
        }
        int idx =
                callIndexByRun.computeIfAbsent(runId, k -> new AtomicInteger(0)).getAndIncrement();
        ComponentInvocation invocation =
                new ComponentInvocation(
                        /* caseId */ runId, // 没有 caseId 时用 runId 作 caseId (PR-6 引入真实 caseId)
                        runId,
                        HarnessComponentType.ROUTER,
                        "rule-based-router",
                        "v1",
                        idx,
                        // InvocationContext 必填, 但 principal/permissionScope/index 已在调用方 beginRun
                        // 时缓存
                        // 为简化第一版, 让测试直接构造 HarnessAwareTaskRouter 时 bind context
                        currentContext());
        InvocationResult<RouterDecision> r =
                provider.invoke(
                        invocation,
                        query,
                        () -> delegate.route(query),
                        RouterDecision.class,
                        adapter);
        return r.result();
    }

    private InvocationContext currentContext() {
        // 注: PR-5 当前简化 — 测试场景固定 context; 真实集成 (PR-6) 由 ToolExecutionContext 注入
        return currentInvocationCtx.get();
    }

    /** 测试用 thread-local bind context (PR-6 时改为 AgentState 注入)。 */
    private final ThreadLocal<InvocationContext> currentInvocationCtx = new ThreadLocal<>();

    public void bindContextForTest(InvocationContext ctx) {
        currentInvocationCtx.set(ctx);
    }
}
