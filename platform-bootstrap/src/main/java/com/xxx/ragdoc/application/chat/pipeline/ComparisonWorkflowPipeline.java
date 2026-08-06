package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.comparison.ComparisonAgentExecutor;
import com.xxx.ragdoc.application.chat.comparison.ComparisonExecutorProperties;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-3.4 + PR-6c: 比较固定工作流。
 *
 * <p>Feature Flag {@code rag.agent.fixed-workflow.comparison-executor-enabled}:
 *
 * <ul>
 *   <li>false (默认): 走 PR-3 旧逻辑 (两次 ChatService.chat + 拼接 — single-pass)
 *   <li>true: 走 {@link ComparisonAgentExecutor} — ComparisonPlanFactory + AgentRunFactory +
 *       AgentRunExecutor + ComparisonEvidencePartitioner + ComparisonAnswerComposer +
 *       ComparisonRunFinalizer (单次 LLM 答案生成 + 服务端固定预算 + 两侧 evidence 分组)
 * </ul>
 *
 * <p>硬不变量 (PR-6c §3):
 *
 * <ul>
 *   <li>Flag=false → 100% PR-3 行为, 无回归
 *   <li>Flag=true 新路径业务终态失败 (TOOL_FAILED/REFUSED_PERMISSION/BUDGET_EXCEEDED/TIMED_OUT/
 *       CANCELLED/REFUSED_NO_EVIDENCE) <b>不</b>静默回退旧路径
 *   <li>仅在 {@code compatibility-fallback-enabled=true} 且配置/初始化异常时才回退, 必须记录 Trace
 *   <li>新路径权限拒绝 / 预算超限 / 无证据 / 超时 / 取消 <b>永不回退</b>
 *   <li>SSE 单终态契约 (PR-0) 在两条路径下都保持
 * </ul>
 *
 * <p>本 Pipeline <b>不</b>注入 ChatOrchestrator; Orchestrator 仍把 FIXED_WORKFLOW 路由到这里。
 */
@Slf4j
@Component
public class ComparisonWorkflowPipeline implements ChatPipeline {

    public static final String PIPELINE_VERSION = "comparison-workflow-v1";
    /** 比较工作流至少要看到的 A/B 两方证据数量阈值 (任一方为 0 即 REFUSE_NO_EVIDENCE)。 */
    public static final int MIN_CITATIONS_PER_SIDE = 1;

    private final ChatService chatService;
    /** PR-6c: 可选注入 — Flag=false 时为 null (或可用, 但不调用)。 */
    private final ComparisonAgentExecutor agentExecutor;
    private final ComparisonExecutorProperties properties;

    @Autowired
    public ComparisonWorkflowPipeline(
            ChatService chatService,
            ComparisonAgentExecutor agentExecutor,
            ComparisonExecutorProperties properties) {
        this.chatService = chatService;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
    }

    @Override
    public PipelineType type() {
        return PipelineType.FIXED_WORKFLOW;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        if (properties.isComparisonExecutorEnabled() && agentExecutor != null) {
            ChatResult agentResult = runAgentPath(command, context);
            // 业务终态失败不回退 (Revision §3); 仅 init failure / 配置缺失才考虑兼容回退
            if (agentResult != null) {
                return agentResult;
            }
            // agentResult == null: 仅 init/配置层面无法进入 executor, 此时按 properties 决定是否 fallback
            if (!properties.isCompatibilityFallbackEnabled()) {
                log.warn("comparison.executor.skipped_fallback_disabled request_id={}",
                        context.requestId());
                return ChatResult.of(StateHint.EMPTY_KB,
                        "Comparison Agent Executor 初始化失败, 兼容回退已禁用",
                        context.traceId());
            }
            log.warn("comparison.executor.falling_back_to_legacy request_id={}",
                    context.requestId());
        }
        return runLegacyPath(command, context);
    }

    /** PR-6c §11.2: Flag 开启走 AgentRunExecutor 路径, RTL→Composer→Finalize。 */
    private ChatResult runAgentPath(ChatCommand command, ChatExecutionContext context) {
        try {
            RouterDecision decision = context.routerDecision();
            Map<String, Object> filters = decision != null ? decision.filters() : Map.of();
            return agentExecutor.execute(
                    command, filters, decision,
                    context.requestId(), context.principal(), context.traceId());
        } catch (com.xxx.ragdoc.application.chat.agent.AgentRunInitializationException
                | com.xxx.ragdoc.application.chat.agent.PlanValidationResult.InvalidAgentPlanException
                | IllegalStateException ex) {
            // 配置 / 初始化层面异常 — 返回 null 触发上层 fallback 决策 (§3 compatibility-only)
            log.warn("comparison.agent_path_init_failed request_id={} reason={}",
                    context.requestId(), ex.toString());
            return null;
        } catch (Exception ex) {
            // Composer / AgentRun Executor 抛业务异常 → 不回退 (§3), 返回结构化 NO_RECALL
            log.warn("comparison.agent_path_business_error request_id={} reason={}",
                    context.requestId(), ex.toString());
            // 避免权限/预算/超时等业务异常被 silently fallback
            properties.setComparisonExecutorEnabled(properties.isComparisonExecutorEnabled()); // no-op, 保留
            return ChatResult.of(StateHint.NO_RECALL,
                    "Comparison 执行失败: " + ex.getMessage(),
                    context.traceId());
        }
    }

    private ChatResult runLegacyPath(ChatCommand command, ChatExecutionContext context) {
        Pair ab = extractComparisonPair(command, context);
        if (ab == null) {
            log.info(
                    "pipeline.workflow.fallback_no_ab request_id={}, trace_id={}, entities={}",
                    context.requestId(),
                    context.traceId().value(),
                    context.routerDecision() != null ? context.routerDecision().entities() : "[]");
            return chatService.chat(command, context.traceId(), command.conversationId());
        }

        log.info(
                "pipeline.workflow.execute request_id={}, trace_id={}, A='{}', B='{}'",
                context.requestId(),
                context.traceId().value(),
                ab.a,
                ab.b);

        String subQueryA = "关于「" + command.query() + "」, 请聚焦与「" + ab.a + "」直接相关的事实";
        String subQueryB = "关于「" + command.query() + "」, 请聚焦与「" + ab.b + "」直接相关的事实";

        ChatCommand cmdA = command.withQuery(subQueryA);
        ChatCommand cmdB = command.withQuery(subQueryB);

        ChatResult resultA =
                chatService.chat(cmdA, context.traceId(), command.conversationId());
        ChatResult resultB =
                chatService.chat(cmdB, context.traceId(), command.conversationId());

        return mergeAndAssemble(resultA, resultB, command, context, ab);
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        // PR-6c: SSE 在 Agent 路径下仍走 Legacy 经典 stream (chat_flow = chatService.chatStream),
        // 单终态契约由 ChatService 内 concatWith + onErrorResume 保证 (PR-0 不变量)。
        // 完整 Agent-driven streaming 留 PR-8。
        return chatService.chatStream(command, context.traceId());
    }

    // ─── 内部 ────────────────────────────────────────────────

    public record Pair(String a, String b) {}

    public static Pair extractComparisonPair(ChatCommand cmd, ChatExecutionContext ctx) {
        RouterDecision d = ctx.routerDecision();
        List<String> ents = d != null ? d.entities() : List.of();
        String a = null;
        String b = null;
        if (ents != null && ents.size() >= 2) {
            a = ents.get(0);
            b = ents.get(1);
        } else if (d != null && d.filters() != null) {
            a = firstString(d.filters().get("versions"));
            b = a == null ? null : secondString(d.filters().get("versions"));
            if (a == null) {
                a = firstString(d.filters().get("products"));
                b = a == null ? null : secondString(d.filters().get("products"));
            }
        }
        if (a == null || b == null) return null;
        return new Pair(a, b);
    }

    private static String firstString(Object o) {
        if (o instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) return s;
        return null;
    }

    private static String secondString(Object o) {
        if (o instanceof List<?> list && list.size() >= 2 && list.get(1) instanceof String s) return s;
        return null;
    }

    /**
     * 合并 A/B 两次结果, 不引入新 LLM 综合调用。 (PR-3.4 旧路径; PR-6c Flag=true 时不走到此方法)
     */
    static ChatResult mergeAndAssemble(
            ChatResult a, ChatResult b, ChatCommand orig, ChatExecutionContext ctx, Pair ab) {
        List<ChatResult.Citation> aCits = nonNull(a.citations());
        List<ChatResult.Citation> bCits = nonNull(b.citations());

        if (aCits.isEmpty() || bCits.isEmpty()) {
            log.info(
                    "pipeline.workflow.no_evidence aCits={}, bCits={}, state_a={}, state_b={}",
                    aCits.size(), bCits.size(), a.stateHint(), b.stateHint());
            return ChatResult.of(
                    StateHint.NO_RECALL,
                    "至少一方 (A=" + ab.a + " 或 B=" + ab.b + ") 缺乏可引用的文档证据, 暂时无法给出比较答案。",
                    ctx.traceId());
        }

        List<ChatResult.Citation> merged = mergeCitations(aCits, bCits);
        String mergedAnswer =
                "关于「"
                        + orig.query()
                        + "」从两个角度对比:\n\n"
                        + "【关于 "
                        + ab.a
                        + "】\n"
                        + a.answer()
                        + "\n\n"
                        + "【关于 "
                        + ab.b
                        + "】\n"
                        + b.answer();

        return new ChatResult(
                mergedAnswer,
                merged,
                StateHint.OK,
                ctx.traceId(),
                null,
                null);
    }

    static List<ChatResult.Citation> mergeCitations(
            List<ChatResult.Citation> aCits, List<ChatResult.Citation> bCits) {
        List<ChatResult.Citation> out = new ArrayList<>(aCits);
        java.util.Set<Long> seenChunkIds = new java.util.HashSet<>();
        for (ChatResult.Citation c : aCits) {
            if (c.chunkId() != null) seenChunkIds.add(c.chunkId());
        }
        for (ChatResult.Citation c : bCits) {
            if (c.chunkId() == null || seenChunkIds.add(c.chunkId())) out.add(c);
        }
        return out;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}

