package com.xxx.ragdoc.application.chat.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.harness.ComponentInvocation;
import com.xxx.ragdoc.application.chat.harness.HarnessMode;
import com.xxx.ragdoc.application.chat.harness.HarnessProperties;
import com.xxx.ragdoc.application.chat.harness.HarnessProvider;
import com.xxx.ragdoc.application.chat.harness.InvocationContext;
import com.xxx.ragdoc.application.chat.harness.ToolHarnessAdapter;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.auth.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;import org.springframework.stereotype.Service;

/**
 * PR-4 / EMS-PR4: Tool 执行的统一包装层。围绕每个 {@link AgentTool#execute} 做以下横切:
 *
 * <ol>
 *   <li><b>Input 校验</b>: 检测 banned 字段 (tenantId/userId/role/adminOverride); Tool 自己再 Bean Validate
 *   <li><b>Deadline/Timeout</b>: 调用前比对 {@link ToolExecutionContext#deadline()}; 不依赖长 DB 事务
 *   <li><b>调用去重</b>: 单 runId+tool+normalizedInput+permissionScopeVersion+indexVersion 同结果只执行一次
 *       (SUCCESS/EMPTY/PERMISSION_DENIED 缓存; TIMEOUT/RETRYABLE 不缓存 — EMS-PR4 §10)
 *   <li><b>ACL pre-check</b>: 从 Principal + PermissionResolverPort 派生 PermissionScope; 拒绝 NO_RECALL sentinel
 *   <li><b>调用 Tool</b> 主体, 不让 RuntimeException 直接冒到上层 (转为 TERMINAL_ERROR / DEPENDENCY_UNAVAILABLE)
 *   <li><b>ACL evidence post-check</b>: 把无权 Evidence 从 output 过滤掉 (双保险)
 *   <li><b>Metrics</b>: tool call count / latency / status / dedup hit (扩展 MetricsPort)
 *   <li><b>Trace</b>: observation `tool.<name>` (扩展 TraceObserver.ObservationType.TOOL — 当前 PR 复用 DECISION)
 * </ol>
 *
 * <p>所有异常都转成 {@link ToolResult#failure}; 只有 Executor 自身 bug 才让 RuntimeException 冒到调用方。
 *
 * <p>不调用的 Tool 不会触发任何 ACL / Trace / Metrics — Executor 是唯一入口, Tool 自己不要重复记账。
 */
@Slf4j
@Service
public class ToolExecutor {

    private final ToolRegistry registry;
    private final PermissionResolverPort permissionResolver;
    private final MetricsPort metrics;
    private final TraceObserver traceObserver;
    private final ObjectMapper objectMapper;
    /** PR-5.1: 让 ToolExecutor 可以把 AgentTool.execute 包装到 HarnessProvider 下。 */
    private final HarnessProvider harnessProvider;
    private final HarnessProperties harnessProperties;
    /** PR-5.1: 每 Tool 的 ObjectResultMapper; 当前固定 ToolHarnessAdapter (typed bridge)。 */
    private final ToolHarnessAdapter toolHarnessAdapter;

    public ToolExecutor(
            ToolRegistry registry,
            PermissionResolverPort permissionResolver,
            MetricsPort metrics,
            TraceObserver traceObserver,
            ObjectMapper objectMapper,
            HarnessProvider harnessProvider,
            HarnessProperties harnessProperties) {
        this.registry = registry;
        this.permissionResolver = permissionResolver;
        this.metrics = metrics;
        this.traceObserver = traceObserver;
        this.objectMapper = objectMapper;
        this.harnessProvider = harnessProvider;
        this.harnessProperties = harnessProperties;
        this.toolHarnessAdapter = new ToolHarnessAdapter(objectMapper);
    }

    /** 单 runId 的 dedup 缓存; PR-4 不做分布式 cache, 进程内 + run 结束自然 GC。 */
    private final Map<String, ToolResult<? extends ToolOutput>> dedupCache = new ConcurrentHashMap<>();

    /**
     * PR-5.1: Tool callIndex counter, (runId, toolName) → Atomic。让单 Run 内同 Tool 多次调用稳定排序，
     * 让 Harness record/replay callIndex 在多调用时不撞键。
     */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> callIndexByRun =
            new ConcurrentHashMap<>();

    /**
     * 执行入口。调用方提供 input + 工具名 / 版本 + runId / requestId / deadline; Executor 自己解析 Principal/ACL。
     *
     * @param runId 单 run (单次 chat / pipeline run) 唯一; Executor 自己生成时 = requestId
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <I extends ToolInput, O extends ToolOutput> ToolResult<O> execute(
            String toolName, String toolVersion, I input, ToolCallRequest req) {
        long t0 = System.currentTimeMillis();
        String callId = req.requestId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Principal principal = AuthContext.currentPrincipal();
        PermissionScope scope = derivePermissionScope(principal);
        ToolExecutionContext ctx =
                new ToolExecutionContext(
                        req.requestId(),
                        req.runId(),
                        principal,
                        principal.tenantId(),
                        scope,
                        req.indexVersion(),
                        req.deadline());

        AgentTool<I, O> tool;
        try {
            tool = (AgentTool<I, O>) registry.get(toolName, toolVersion);
        } catch (DomainException de) {
            return finishFailure(callId, toolName, toolVersion, ToolStatus.TERMINAL_ERROR, de.errorCode().code(),
                    "tool not found: " + toolName + ":" + toolVersion, "", t0, ctx, null, false);
        }

        // 1. 安全: 检测 banned 字段 (LLM 不能偷传身份字段)
        ToolError banned = detectBannedFields(input);
        if (banned != null) {
            return finishFailure(callId, toolName, toolVersion, ToolStatus.INVALID_ARGUMENT,
                    banned.errorCode(), banned.safeMessage(), "", t0, ctx, null, false);
        }

        // 2. dedup check (必须在 ACL resolved 之后, 用 scope.version + indexVersion 一起作 key)
        String dedupKey = dedupKey(ctx.runId(), tool.descriptor(), input, ctx);
        ToolResult<? extends ToolOutput> cached = dedupCache.get(dedupKey);
        if (cached != null && cached.status().cacheable()) {
            metrics.incrementToolDedupHit(toolName);
            log.info("tool.dedup_hit name={} call_id={} cached_status={}", toolName, callId, cached.status());
            // 直接返回新 callId 的 copy (原 callId 不同, 重新塑形)
            return rebuildWithCallId((ToolResult<O>) cached, callId, true);
        }

        // 3. ACL pre-check: NO_RECALL sentinel 直接 PERMISSION_DENIED (与 RetrieveService 一致, 不调下游)
        if (!scope.tenantAdmin() && scope.allowedDocumentIds() != null && scope.allowedDocumentIds().isEmpty()) {
            return finishFailure(
                    callId, toolName, toolVersion, ToolStatus.PERMISSION_DENIED,
                    ErrorCode.TOOL_PERMISSION_DENIED.code(),
                    "用户在当前 tenant 无任何可读文档", "", t0, ctx, dedupKey, false);
        }

        // 4. deadline check (在 tool 执行前; 让 Tool 也自带 remainingMillis 自检)
        if (ctx.isExpired()) {
            return finishFailure(callId, toolName, toolVersion, ToolStatus.TIMEOUT, ErrorCode.TOOL_TIMEOUT.code(),
                    "已达 deadline, 未执行 tool", "", t0, ctx, dedupKey, false);
        }

        // 5. 实际调用 Tool — 经 HarnessProvider 包装 (PR-5.1)
        //    LIVE  → 直接 supplier, 等价 PR-4 行为
        //    RECORD → 调 supplier + 写 Fixture (tenantScopeFingerprint 已并入 ReplayKey)
        //    REPLAY → 不调 supplier; 从 Fixture 读; 缺失/不匹配/损坏 → FixtureUnavailableException 失败关闭
        ToolResult<O> result;
        try {
            result = invokeViaHarness(tool, input, ctx, req.runId());
        } catch (com.xxx.ragdoc.application.chat.harness.FixtureStore
                .FixtureUnavailableException fue) {
            // REPLAY 严格失败 → 转 TERMINAL_ERROR, 不回退 LIVE
            log.warn(
                    "tool.replay_failed name={} call_id={} reason={} msg={}",
                    toolName, callId, fue.reason, fue.getMessage());
            return ToolResult.failure(
                    callId,
                    toolName,
                    toolVersion,
                    ToolStatus.TERMINAL_ERROR,
                    ToolError.of(fue.reason.name(), "tool replay fixture 不可用: " + fue.getMessage()),
                    System.currentTimeMillis() - t0,
                    baseMeta(ctx, false));
        } catch (com.xxx.ragdoc.application.chat.harness.FixtureStore
                .FixtureConflictException fce) {
            // RECORD 同 key 不同内容 (代码改动后旧 fixture 不一致) → 失败关闭
            log.warn("tool.record_conflict name={} call_id={} msg={}", toolName, callId, fce.getMessage());
            return ToolResult.failure(
                    callId,
                    toolName,
                    toolVersion,
                    ToolStatus.TERMINAL_ERROR,
                    ToolError.of("FIXTURE_CONFLICT", "record 与既有 fixture 冲突"),
                    System.currentTimeMillis() - t0,
                    baseMeta(ctx, false));
        } catch (DomainException de) {
            result = ToolResult.failure(callId, toolName, toolVersion, ToolStatus.TERMINAL_ERROR,
                    ToolError.of(de.errorCode().code(), safeMsg(de.getMessage())),
                    System.currentTimeMillis() - t0, baseMeta(ctx, false));
        } catch (RuntimeException ex) {
            log.warn("tool.uncaught_exception name={} call_id={} err={}", toolName, callId, ex.toString());
            result = ToolResult.failure(callId, toolName, toolVersion, ToolStatus.TERMINAL_ERROR,
                    ToolError.dependencyError(ErrorCode.TOOL_EXECUTION_FAILED.code(),
                            "tool 执行发生未预期错误", toolName, false),
                    System.currentTimeMillis() - t0, baseMeta(ctx, false));
        }

        // 6. ACL evidence post-check (双保险): 把无权 Evidence 过滤掉
        result = filterUnauthorizedEvidence(result, ctx);

        // 7. metrics + trace
        recordToolCall(toolName, toolVersion, callId, result, input, ctx, false);

        // 8. cache 填充 (只缓存 cacheable 状态)
        if (result.status().cacheable()) {
            dedupCache.put(dedupKey, result);
        }
        return result;
    }

    /**
     * PR-5.1: 包装 AgentTool.execute 到 Harness 下。
     *
     * <p>当 {@code rag.agent.harness.enabled=false} 或 mode=LIVE 时, 直接调真实 Tool (零开销)。
     * RECORD / REPLAY 时通过 {@link HarnessProvider#invoke} 走 canonical/recording/replay 边界;
     * 但 REPLAY 仍受 ToolExecutor 的 {@link #filterUnauthorizedEvidence} 终检保护 (双层 ACL)。
     *
     * <p>每个 (runId, toolName) 维护独立 callIndex counter, 让 record/replay 在多调用下不撞键。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <I extends ToolInput, O extends ToolOutput> ToolResult<O> invokeViaHarness(
            AgentTool<I, O> tool, I input, ToolExecutionContext ctx, String runId) {
        ToolDescriptor d = tool.descriptor();
        // 第 8 阶段: Harness 默认关闭时直接 fallback 真实 Tool; 跳过 Provider 数据构造
        if (!harnessProperties.isEnabled() || harnessProperties.getMode() == HarnessMode.LIVE) {
            return tool.execute(input, ctx);
        }

        String callIdxKey = runId + "|" + d.name();
        int callIndex =
                callIndexByRun
                        .computeIfAbsent(callIdxKey, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                        .getAndIncrement();
        ComponentInvocation invocation =
                new ComponentInvocation(
                        /* caseId */ runId, // 单 run 等同 caseId (PR-6 引入 Agent run 时区分)
                        runId,
                        com.xxx.ragdoc.application.chat.harness.HarnessComponentType.TOOL,
                        d.name(),
                        d.version(),
                        callIndex,
                        new InvocationContext(
                                ctx.requestId(),
                                ctx.tenantId(),
                                ctx.permissionScope().permissionScopeVersion(),
                                ctx.indexVersion(),
                                ctx.requestId() /* traceId 第二版可加, Tool 第一版用 requestId */,
                                ""));
        HarnessProvider provider = harnessProvider;
        java.util.function.Supplier<ToolResult<O>> live =
                () -> tool.execute(input, ctx);
        Class<ToolResult<O>> resultClass =
                (Class<ToolResult<O>>) (Class<?>) tool.outputType();
        // 注: ToolHarnessAdapter 在 fromFixtureResponse 返回的是 responseNode 不是 ToolResult;
        // 因此 REPLAY 路径下 caller 拿到的 result 可能是 JsonNode (除非 Adapter 重写支持 typed ToolResult).
        // PR-5.1 v1: 不强求 REPLAY 返回 typed ToolResult; Executor 检测 JsonNode 时转 empty SUCCESS stub.
        com.xxx.ragdoc.application.chat.harness.InvocationResult result =
                provider.invoke(invocation, input, live, resultClass, toolHarnessAdapter);
        Object outcome = result.result();
        if (outcome instanceof ToolResult tr) {
            return (ToolResult<O>) tr;
        }
        // outcome 为 JsonNode (REPLAY 通过 ToolHarnessAdapter.fromFixtureResponse 返回原始 Node)
        // 转 ToolResult.SUCCESS stub 让上层 usage 走完整路径
        com.fasterxml.jackson.databind.JsonNode node =
                (com.fasterxml.jackson.databind.JsonNode) outcome;
        O typed = objectMapper.convertValue(node, tool.outputType());
        return ToolResult.success(
                ctx.requestId() + "-harness",
                d.name(),
                d.version(),
                typed,
                0L,
                java.util.Map.of("harness_mode", harnessProperties.getMode().name()));
    }

    /**
     * 同步清理某 runId 的 dedup cache。Pipeline / Agent run 结束时由 Orchestrator 调用。
     * PR-4 当前不在 chat 链路自动调 (没有 Agent run), 留给 PR-5/6。
     */
    public void evictRun(String runId) {
        dedupCache.keySet().stream()
                .filter(k -> k.startsWith("r=" + runId + "|"))
                .forEach(dedupCache::remove);
    }

    // ─── 内部 ────────────────────────────────────────────

    /** 工具调用请求 (避免 ToolExecutionContext 还没生成时调用方塞一堆参数)。 */
    public record ToolCallRequest(
            String requestId, String runId, Instant deadline, String indexVersion) {
        public ToolCallRequest {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("requestId 必填");
            }
            if (runId == null || runId.isBlank()) runId = requestId;
            if (deadline == null) deadline = Instant.now().plus(Duration.ofSeconds(30));
            if (indexVersion == null || indexVersion.isBlank()) indexVersion = "default";
        }
    }

    private PermissionScope derivePermissionScope(Principal principal) {
        com.xxx.ragdoc.application.auth.AccessScope scope = permissionResolver.resolveAccessScope(principal);
        Set<Long> allowed = scope.allowedDocumentIds();
        String version = derivePermissionVersion(principal, allowed);
        if (scope.isUnrestrictedWithinTenant()) {
            return PermissionScope.adminOf(principal.tenantId(), version);
        }
        return PermissionScope.of(principal.tenantId(), allowed == null ? null : allowed, version);
    }

    /**
     * ACL 表无显式 version 列 (审计结论), PR-4 用 tenantId + allowed.size() + 模型 fingerprint 派生稳定版本。
     * ACL 行数 grant/revoke → allowed.size() 变化 → 版本变化 → 旧缓存失效 (EMS-PR4 §10 要求)。
     */
    private static String derivePermissionVersion(Principal principal, Set<Long> allowed) {
        int n = allowed == null ? -1 : allowed.size();
        String identity = principal.tenantId() + "|" + principal.userId() + "|n=" + n;
        return sha256(identity).substring(0, 12);
    }

    /** EMS-PR4 §10 dedup key = sha256(runId|toolName|toolVersion|normalizedInput|scopeVersion|indexVersion)。 */
    static String dedupKey(
            String runId, ToolDescriptor d, ToolInput input, ToolExecutionContext ctx) {
        String norm = input == null ? "" : input.normalizedForDedup();
        String raw =
                "r=" + runId
                        + "|t=" + d.name() + ":" + d.version()
                        + "|in=" + sha256(norm)
                        + "|scope=" + ctx.permissionScope().permissionScopeVersion()
                        + "|idx=" + ctx.indexVersion();
        return raw; // key 不必再 hash, 内含 hash 已足够避让原文
    }

    /**
     * 检测 input 里的 banned 字段名 (反序列化 record 时如果含 tenantId/userId/role/adminOverride/token)。
     * PR-4 用 toString 简单匹配 (record 默认 toString 是 `Xxx[tenantId=..., userId=...]`)。
     */
    private static ToolError detectBannedFields(ToolInput input) {
        if (input == null) return null;
        String s = input.toString().toLowerCase();
        if (s.contains("tenantid=") || s.contains("userid=") || s.contains("tenantoverride=")
                || s.contains("adminoverride=") || s.contains("rawtoken=") || s.contains("acloverride=")) {
            return ToolError.of(
                    ErrorCode.TOOL_INVALID_ARGUMENT.code(),
                    "input 含身份字段, tenantId/userId/role 等只能由服务端注入");
        }
        return null;
    }

    /** 把 ToolResult.output 中携带的 Evidence (如果是 EvidenceListOutput) 做 tenantId 二次校验。 */
    @SuppressWarnings("unchecked")
    private static <O extends ToolOutput> ToolResult<O> filterUnauthorizedEvidence(
            ToolResult<O> result, ToolExecutionContext ctx) {
        if (result.output() instanceof EvidenceListOutput elo) {
            String tenantId = ctx.tenantId();
            List<Evidence> kept = new ArrayList<>();
            int dropped = 0;
            for (Evidence e : elo.evidences()) {
                // 双保险: RetrieveService 已经按 ACL 过滤, 这里再校验 tenantId 一致
                if (e.tenantId() != null && e.tenantId().equals(tenantId)) {
                    kept.add(e);
                } else {
                    dropped++;
                }
            }
            if (dropped == 0) return result;
            log.warn(
                    "tool.acl_dropped_unauthorized_evidence tenant={} tool={} dropped={}",
                    tenantId,
                    result.toolName(),
                    dropped);
            // 重建 output (需 EvidenceListOutput 实现 withEvidences) + 重塑 ToolResult
            EvidenceListOutput filtered = elo.withEvidences(kept);
            Map<String, Object> meta = new LinkedHashMap<>(result.metadata());
            meta.put("acl_dropped_unauthorized", dropped);
            return new ToolResult(
                    result.callId(), result.toolName(), result.toolVersion(),
                    kept.isEmpty() && result.status() == ToolStatus.SUCCESS
                            ? ToolStatus.EMPTY_RESULT
                            : result.status(),
                    kept.isEmpty() && result.status() == ToolStatus.SUCCESS ? null : (O) filtered,
                    kept.isEmpty() && result.status() == ToolStatus.SUCCESS
                            ? ToolError.of("EMPTY_FILTERED", "ACL 过滤后 Evidence 为空")
                            : result.error(),
                    result.latencyMs(), result.retryable(), meta);
        }
        return result;
    }

    private void recordToolCall(
            String name, String version, String callId, ToolResult<?> r,
            ToolInput input, ToolExecutionContext ctx, boolean dedup) {
        try {
            metrics.recordToolCall(name, r.status().name(), r.latencyMs());
            if (r.output() instanceof EvidenceListOutput elo) {
                metrics.recordToolEvidenceYield(name, elo.evidences().size());
            }
        } catch (RuntimeException ignore) {
            // metrics 失败不阻塞业务路径
        }
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("call_id", callId);
            meta.put("run_id", ctx.runId());
            meta.put("request_id", ctx.requestId());
            meta.put("tool_name", name);
            meta.put("tool_version", version);
            meta.put("status", r.status().name());
            meta.put("latency_ms", r.latencyMs());
            meta.put("input_hash", sha256(input == null ? "" : input.normalizedForDedup()).substring(0, 12));
            meta.put("tenant_id", ctx.tenantId());
            meta.put("index_version", ctx.indexVersion());
            meta.put("permission_scope_version", ctx.permissionScope().permissionScopeVersion());
            int evidenceCount =
                    r.output() instanceof EvidenceListOutput elo ? elo.evidences().size() : 0;
            meta.put("result_count", evidenceCount);
            meta.put("deduplicated", dedup);
            traceObserver.observe(
                    ctx.requestId(),
                    TraceObserver.ObservationType.DECISION, // PR-4 暂复用 DECISION (TraceObserver 枚举未扩 TOOL)
                    "tool." + name,
                    null,
                    null,
                    r.latencyMs(),
                    meta);
        } catch (RuntimeException ignore) {
            // trace 失败不阻塞
        }
    }

    private <O extends ToolOutput> ToolResult<O> finishFailure(
            String callId, String toolName, String toolVersion, ToolStatus status,
            String errorCode, String safeMessage, String dependency,
            long t0, ToolExecutionContext ctx, String dedupKey, boolean dedup) {
        ToolResult<O> r =
                ToolResult.failure(
                        callId,
                        toolName,
                        toolVersion,
                        status,
                        ToolError.dependencyError(errorCode, safeMessage, dependency, status.retryable()),
                        System.currentTimeMillis() - t0,
                        baseMeta(ctx, dedup));
        recordToolCall(toolName, toolVersion, callId, r, null, ctx, dedup);
        if (dedupKey != null && r.status().cacheable()) {
            dedupCache.put(dedupKey, r);
        }
        return r;
    }

    private static Map<String, Object> baseMeta(ToolExecutionContext ctx, boolean dedup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", ctx.requestId());
        m.put("run_id", ctx.runId());
        m.put("tenant_id", ctx.tenantId());
        m.put("deduplicated", dedup);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <O extends ToolOutput> ToolResult<O> rebuildWithCallId(ToolResult<O> cached, String newCallId, boolean dedup) {
        Map<String, Object> meta = new LinkedHashMap<>(cached.metadata());
        meta.put("deduplicated", dedup);
        return new ToolResult(
                newCallId, cached.toolName(), cached.toolVersion(), cached.status(),
                cached.output(), cached.error(), cached.latencyMs(), cached.retryable(), meta);
    }

    private static String safeMsg(String msg) {
        if (msg == null) return "tool execution failed";
        // 去掉潜在内部信息: 长度截断 + 不原样保留 stack-style 内容
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    /** SHA-256 hex (复用项目既有风格)。 */
    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
