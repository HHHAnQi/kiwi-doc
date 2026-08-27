package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.pipeline.ChatExecutionContext;
import com.xxx.ragdoc.application.chat.pipeline.ChatPipeline;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-7c.3c / EMS-PR7 §8 + §9: 薄适配层 — 调 {@link PlannedAgentExecutionCoordinator#prepare} 取得
 * PreparedGroundedAnswer → 单次 GroundedAnswerComposer → ChatResult (同步); SSE 留 PR-7c.3c-2。
 *
 * <p>Run 终态由 {@link PlannedAgentExecutionCoordinator} 通过 Finalizer 写到 READY_TO_ANSWER (成功时)
 * 或对应失败终态; 本 Pipeline 在 Answer Composer 成功后再 Finalize 写 ANSWERED, Answer 失败按对应终态 (TIMED_OUT /
 * SYSTEM_FAILED)。
 *
 * <p>ChatOrchestrator 已经按 ExecutionStrategyResolver 路由策略为 PLANNED_AGENT 时获取本 Bean。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannedAgentPipeline implements ChatPipeline {

    private final PlannedAgentExecutionCoordinator coordinator;
    private final DefaultEvidenceGroundedAnswerComposer answerComposer;
    private final PlannedAgentRunFinalizer runFinalizer;
    private final com.xxx.ragdoc.application.chat.planner.PlannerProperties plannerProperties;
    /** P0-3: 执行预算可配(原硬编码 pr6Default maxReplans=0 → Replan 必 BUDGET_ZERO)。 */
    private final com.xxx.ragdoc.application.chat.agent.AgentBudgetProperties budgetProps;
    /**
     * 检索锚定(2026-08-25): 原查询 hybrid 检索结果作为"保底"加入 Agentic 证据池。
     * 根因: LLM 分解后的子查询方向跑偏(实测 Dubbo/Seata 题检索到 Sentinel/RocketMQ),
     * 而原查询一次性 hybrid 检索精准命中 — include_original 模式(LangChain
     * MultiQueryRetriever), 即使所有子查询跑偏, 原查询结果保证相关证据存在。
     */
    private final com.xxx.ragdoc.application.chat.RetrieveService retrieveService;

    @Override
    public PipelineType type() {
        return PipelineType.PLANNED_AGENT;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        if (!plannerProperties.isEnabled()) {
            // 能力关闭属于部署配置错误，不能伪装成“没有召回”。
            throw new DomainException(
                    ErrorCode.AGENTIC_MODE_UNAVAILABLE, "Planned Agent pipeline 未启用");
        }
        // P1修复(短板8): 多轮改写 — 有 conversationId 时先做指代消解
        String effectiveQuery = resolveEffectiveQuery(command);
        RouterDecision decision = context.routerDecision();
        var prepared =
                coordinator.prepare(
                        effectiveQuery,
                        decision,
                        context.requestId(),
                        context.principal(),
                        CancellationTokenSource.CancellationToken.never(),
                        allowedToolDescriptors(),
                        buildAgenticPolicy());
        if (!prepared.ok()) {
            return ChatResult.of(StateHint.NO_RECALL, humanizeFailure(prepared), context.traceId());
        }
        PlannedAgentExecutionCoordinator.PreparedGroundedAnswer p = prepared.prepared();
        // Agent 过程可视化: 透出 runId(前端据此拉 /agent/runs/{id} 渲染执行步骤面板)
        if (p.runId() != null) {
            org.slf4j.MDC.put("rag.agentRunId", p.runId());
        }
        // 检索锚定: 原查询 hybrid 检索结果合并到 Agentic 证据池(去重, 上限20)
        java.util.List<com.xxx.ragdoc.application.chat.evidence.Evidence> anchoredEvidence =
                anchorWithOriginalQuery(command, p.evidence(), context);

        // 单次 Answer generation
        EvidenceGroundedAnswerComposer.GroundedAnswer answer;
        try {
            answer =
                    answerComposer.compose(
                            new EvidenceGroundedAnswerComposer.GroundedAnswerRequest(
                                    command.query(),
                                    p.requirements(),
                                    p.coverage(),
                                    anchoredEvidence,
                                    context.principal().tenantId(),
                                    p.runId()));
        } catch (Exception ex) {
            log.warn("planned.answer_composer_failed run={} err={}", p.runId(), ex.toString());
            runFinalizer.finalize(
                    p.runId(),
                    p.readyRunVersion(),
                    java.util.Set.of(AgentRunStatus.READY_TO_ANSWER),
                    AgentRunStatus.SYSTEM_FAILED,
                    "ANSWER_COMPOSER_FAILED",
                    p.usage(),
                    p.reservation());
            return ChatResult.of(StateHint.NO_RECALL, "答案生成失败", context.traceId());
        }
        // Final Answer Cas ANSWERED
        PlannedAgentRunFinalizer.FinalizeOutcome outcome = runFinalizer.finalize(
                p.runId(),
                p.readyRunVersion(),
                java.util.Set.of(AgentRunStatus.READY_TO_ANSWER),
                AgentRunStatus.ANSWERED,
                "PLANNED_ANSWER_READY",
                p.usage(),
                p.reservation());
        if (!outcome.written() && !outcome.idempotent()) {
            log.warn(
                    "planned.answer_finalize_conflict run={} winner={} conflict={}",
                    p.runId(),
                    outcome.effectiveTerminal(),
                    outcome.conflict());
            return ChatResult.of(StateHint.NO_RECALL, "Agent 运行状态已被取消或终止", context.traceId());
        }
        // Citation (PR-7c.3c 简化: 用 evidenceIds 直接转 Citation, disabled verifier 走安全 skip)
        var citations = buildCitations(p);
        return new ChatResult(
                answer.text(), citations, StateHint.OK, context.traceId(), null, null);
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        CancellationTokenSource cts = new CancellationTokenSource();
        // prepare 包含 Planner/检索/工具远程 IO，必须延迟到订阅并移出 WebFlux 事件循环。
        // doOnCancel 把断连传播到每个 Step 前置关口；具体远程客户端仍需各自超时兜底。
        return Flux.defer(() -> streamPrepared(command, context, cts))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnCancel(cts::cancel);
    }

    private Flux<ChatStreamEvent> streamPrepared(
            ChatCommand command, ChatExecutionContext context, CancellationTokenSource cts) {
        // PR-7c.3c-2: SSE 流式路径 — 与同步共享 prepare; 单终态契约 (PR-0)
        RouterDecision decision = context.routerDecision();
        PlannedAgentExecutionCoordinator.PrepareResult prepared;
        try {
            prepared =
                    coordinator.prepare(
                            command.query(),
                            decision,
                            context.requestId(),
                            context.principal(),
                            cts.token(),
                            allowedToolDescriptors(),
                            buildAgenticPolicy());
        } catch (RuntimeException ex) {
            log.warn(
                    "planned.sse.prepare_failed req={} err={}", context.requestId(), ex.toString());
            return Flux.just(
                    (ChatStreamEvent)
                            new ChatStreamEvent.ErrorEvent(
                                    context.traceId() == null ? "" : context.traceId().value(),
                                    "PLANNED_PREPARE_FAILED:" + ex.getMessage()));
        }
        if (!prepared.ok()) {
            return Flux.just(
                    (ChatStreamEvent)
                            new ChatStreamEvent.ErrorEvent(
                                    context.traceId() == null ? "" : context.traceId().value(),
                                    humanizeFailure(prepared)));
        }
        PlannedAgentExecutionCoordinator.PreparedGroundedAnswer p = prepared.prepared();
        // Agent 过程可视化: 透出 runId(前端据此拉 /agent/runs/{id} 渲染执行步骤面板)
        if (p.runId() != null) {
            org.slf4j.MDC.put("rag.agentRunId", p.runId());
        }
        if (cts.token().isCancelled()) {
            runFinalizer.finalize(
                    p.runId(),
                    p.readyRunVersion(),
                    java.util.Set.of(AgentRunStatus.READY_TO_ANSWER),
                    AgentRunStatus.CANCELLED,
                    "USER_CANCELLED",
                    p.usage(),
                    p.reservation());
            return Flux.just(
                    (ChatStreamEvent)
                            new ChatStreamEvent.ErrorEvent(
                                    context.traceId() == null ? "" : context.traceId().value(),
                                    "已取消"));
        }
        EvidenceGroundedAnswerComposer.GroundedAnswerRequest req =
                new EvidenceGroundedAnswerComposer.GroundedAnswerRequest(
                        command.query(),
                        p.requirements(),
                        p.coverage(),
                        p.evidence(),
                        context.principal().tenantId(),
                        p.runId());
        // 流式 Answer + 单 DoneEvent 收尾 (终态由 Finalizer 写)
        return answerComposer.stream(req)
                .map(token -> (ChatStreamEvent) token)
                .concatWith(
                        reactor.core.publisher.Mono.fromCallable(
                                () -> {
                                    PlannedAgentRunFinalizer.FinalizeOutcome outcome =
                                            runFinalizer.finalize(
                                            p.runId(),
                                            p.readyRunVersion(),
                                            java.util.Set.of(AgentRunStatus.READY_TO_ANSWER),
                                            AgentRunStatus.ANSWERED,
                                            "PLANNED_ANSWER_STREAMED",
                                            p.usage(),
                                            p.reservation());
                                    if (!outcome.written() && !outcome.idempotent()) {
                                        throw new IllegalStateException(
                                                "Agent 终态已被抢占: "
                                                        + outcome.effectiveTerminal());
                                    }
                                    return (ChatStreamEvent)
                                            new ChatStreamEvent.DoneEvent(
                                                    context.traceId() == null
                                                            ? ""
                                                            : context.traceId().value(),
                                                    "OK");
                                }))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "planned.sse.answer_failed run={} err={}",
                                    p.runId(),
                                    err.toString());
                            runFinalizer.finalize(
                                    p.runId(),
                                    p.readyRunVersion(),
                                    java.util.Set.of(AgentRunStatus.READY_TO_ANSWER),
                                    AgentRunStatus.SYSTEM_FAILED,
                                    "ANSWER_STREAM_FAILED",
                                    p.usage(),
                                    p.reservation());
                            return reactor.core.publisher.Flux.just(
                                    (ChatStreamEvent)
                                            new ChatStreamEvent.ErrorEvent(
                                                    context.traceId() == null
                                                            ? ""
                                                            : context.traceId().value(),
                                                    "答案流失败"));
                        });
    }

    /**
     * P0-3: 预算从 rag.agent.budget.* 构建(原 pr6Default maxReplans=0 使 Replan 必死);
     * maxReplans 取 budget 与 planner 配置的较小值, 防两处口径漂移。
     */
    private com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy buildAgenticPolicy() {
        int maxReplans = Math.min(budgetProps.getMaxReplans(), plannerProperties.getMaxReplans());
        com.xxx.ragdoc.application.chat.agent.AgentBudget budget =
                new com.xxx.ragdoc.application.chat.agent.AgentBudget(
                        budgetProps.getMaxSteps(),
                        budgetProps.getMaxToolCalls(),
                        budgetProps.getMaxPlannerCalls(),
                        maxReplans,
                        budgetProps.getMaxExecutionMillis(),
                        0,
                        0,
                        budgetProps.getMaxTotalTokens(),
                        java.math.BigDecimal.ZERO);
        return new com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy(
                budget,
                java.time.Instant.now().plusMillis(budget.maxExecutionMillis()),
                java.util.Set.of(
                        "semantic_search",
                        "keyword_search",
                        "metadata_search",
                        "document_fetch",
                        "citation_verify"),
                20,
                4000,
                true,
                false,
                true);
    }

    /**
     * 检索锚定: 原查询走一次 hybrid 检索(与 Classic 相同), 结果合并到 Agentic 证据池。
     * 去重: 按 content 前200字符; 上限: 合并后最多 20 条(Composer 上下文预算内)。
     */
    private java.util.List<com.xxx.ragdoc.application.chat.evidence.Evidence> anchorWithOriginalQuery(
            ChatCommand command,
            java.util.List<com.xxx.ragdoc.application.chat.evidence.Evidence> agenticEvidence,
            ChatExecutionContext context) {
        try {
            com.xxx.ragdoc.application.chat.RetrieveService.RetrieveResult result = retrieveService.retrieve(command);
            if (result.items().isEmpty()) return agenticEvidence;
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (var e : agenticEvidence) {
                if (e.content() != null) existing.add(e.content().substring(0, Math.min(200, e.content().length())));
            }
            java.util.List<com.xxx.ragdoc.application.chat.evidence.Evidence> merged = new java.util.ArrayList<>(agenticEvidence);
            String tenant = context != null && context.principal() != null ? context.principal().tenantId() : "default";
            for (var c : result.items()) {
                String content = c.llmContext() != null ? c.llmContext() : c.snippet();
                if (content == null || content.isBlank()) continue;
                String key = content.substring(0, Math.min(200, content.length()));
                if (existing.contains(key)) continue;
                existing.add(key);
                merged.add(com.xxx.ragdoc.application.chat.evidence.Evidence.of(
                        tenant, c.docId(), c.chunkId(), null, content, (double) c.score(), 0.0,
                        "original_query_anchor", java.util.Map.of("anchor", "original_query")));
                if (merged.size() >= 20) break;
            }
            log.info("planned.evidence_anchored agentic={} original={} merged={}",
                    agenticEvidence.size(), result.items().size(), merged.size());
            return merged;
        } catch (Exception e) {
            log.warn("planned.anchor_failed: {}", e.getMessage());
            return agenticEvidence;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort queryContextualizer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xxx.ragdoc.application.chat.conversation.port.ConversationStore conversationStore;

    private String resolveEffectiveQuery(ChatCommand command) {
        if (command.conversationId() == null || command.conversationId().isBlank()) return command.query();
        if (queryContextualizer == null || conversationStore == null) return command.query();
        try {
            var ctx = conversationStore.findById(command.conversationId()).orElse(null);
            if (ctx == null || !ctx.isEnabled() || ctx.recentTurns().isEmpty()) return command.query();
            var result = queryContextualizer.contextualize(command.query(), ctx.recentTurns());
            log.info("planned.multi_turn_rewrite conv_id={}, rewritten='{}'", command.conversationId(), result.retrieveQuery().substring(0, Math.min(40, result.retrieveQuery().length())));
            return result.retrieveQuery();
        } catch (Exception e) {
            log.warn("planned.multi_turn_rewrite_failed: {}", e.getMessage());
            return command.query();
        }
    }

    private java.util.List<com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor>
            allowedToolDescriptors() {
        return java.util.List.of(
                new com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor(
                        "semantic_search", "v1", "semantic search", java.util.Map.of()),
                new com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor(
                        "metadata_search", "v1", "metadata search", java.util.Map.of()),
                new com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor(
                        "keyword_search", "v1", "keyword search", java.util.Map.of()),
                new com.xxx.ragdoc.application.chat.planner.PlannerToolDescriptor(
                        "document_fetch", "v1", "document fetch", java.util.Map.of()));
    }

    private static java.util.List<ChatResult.Citation> buildCitations(
            PlannedAgentExecutionCoordinator.PreparedGroundedAnswer p) {
        java.util.List<ChatResult.Citation> out = new java.util.ArrayList<>();
        for (var e : p.evidence()) {
            out.add(
                    new ChatResult.Citation(
                            e.chunkId(),
                            e.documentId(),
                            0,
                            safeSnippet(e.content()),
                            e.content(),
                            java.util.List.of(),
                            null));
        }
        return out;
    }

    private static String safeSnippet(String content) {
        if (content == null) return "";
        return content.length() <= 120 ? content : content.substring(0, 120) + "...";
    }

    private static String humanizeFailure(PlannedAgentExecutionCoordinator.PrepareResult r) {
        if (r.failureTerminal() != null) {
            return switch (r.failureTerminal()) {
                case REFUSED_NO_EVIDENCE -> "证据不足, 无法回答 (" + safe(r.failureReason()) + ")";
                case REFUSED_CONFLICT -> "证据冲突, 无法回答";
                case REFUSED_PERMISSION -> "权限不足";
                case TOOL_FAILED -> "工具调用失败";
                case BUDGET_EXCEEDED -> "处理预算超限";
                case TIMED_OUT -> "处理超时";
                case CANCELLED -> "已取消";
                case SYSTEM_FAILED -> "内部错误";
                default -> "处理失败";
            };
        }
        return "无法处理: " + safe(r.failureReason());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
