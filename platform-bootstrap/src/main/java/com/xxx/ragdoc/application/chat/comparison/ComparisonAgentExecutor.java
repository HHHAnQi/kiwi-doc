package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.agent.AgentRunFactory;
import com.xxx.ragdoc.application.chat.agent.AgentRunExecutor;
import com.xxx.ragdoc.application.chat.agent.AgentRunHandle;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunResult;
import com.xxx.ragdoc.application.chat.agent.CancellationTokenSource;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.comparison.ComparisonEvidencePartitioner.PartitionResult;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6c / EMS-PR6c §4 + §11.2: 把 AgentRunExecutor + ComparisonPlanFactory +
 * EvidencePartitioner + ComparisonAnswerComposer + Run Finalizer 串成同步 {@link ChatResult}。
 *
 * <p>SSE 在 {@link ComparisonWorkflowPipelineAdapter#stream} 单独实现 (维持单终态契约)。
 *
 * <p>关键: <b>同步</b>只调用一次 LLM (composer.compose), 不再 PR-3 旧版两次 ChatService.chat。
 *
 * <p>本协调器不被 ChatOrchestrator 注入; 由 {@link ComparisonWorkflowPipelineAdapter} 在 Flag 开启时调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonAgentExecutor {

    private final ComparisonPlanFactory planFactory;
    private final AgentRunFactory runFactory;
    private final AgentRunExecutor executor;
    private final ComparisonEvidencePartitioner partitioner;
    private final ComparisonAnswerComposer composer;
    private final ComparisonRunFinalizer finalizer;

    /**
     * 同步执行。
     *
     * @param command 原 ChatCommand
     * @param routerDecisionFilters {@code RouterDecision.filters()}; 服务端派生
     * @param routerDecision RouterDecision
     * @param requestId context.requestId
     * @param principal 来自 ChatExecutionContext
     * @param traceId Trace
     */
    public ChatResult execute(
            ChatCommand command,
            Map<String, Object> routerDecisionFilters,
            com.xxx.ragdoc.application.chat.router.RouterDecision routerDecision,
            String requestId,
            Principal principal,
            TraceId traceId) throws Exception {

        // 1. PlanFactory
        ComparisonPlanBuildResult planResult = planFactory.build(
                command.query(), routerDecision, routerDecisionFilters);
        if (!planResult.valid()) {
            log.info("comparison.executor.plan_invalid reason={} request_id={}",
                    planResult.invalidReason(), requestId);
            // 调用方决定是否走 legacy compatibility fallback; 不可静默成功 (§3)
            return ChatResult.of(StateHint.NO_RECALL,
                    "无法构造比较计划: " + planResult.invalidReason(), traceId);
        }

        // 2. RunFactory (PlanValidator + 单事务初始化)
        InitializedRun init;
        try {
            init = runFactory.create(
                    planResult.plan(),
                    planResult.policy(),
                    principal,
                    requestId,
                    "FIXED_WORKFLOW",
                    "rule-based-v1",
                    "toolset-v1",
                    "default",
                    "LIVE");
        } catch (RuntimeException ex) {
            log.warn("comparison.executor.run_init_failed request_id={} err={}",
                    requestId, ex.getMessage());
            return ChatResult.of(StateHint.EMPTY_KB, "Agent Run 初始化失败", traceId);
        }

        // 3. Executor
        AgentRunResult runResult = executor.execute(
                planResult.plan(),
                planResult.policy(),
                init,
                principal.tenantId(),
                requestId,
                CancellationTokenSource.CancellationToken.never());

        // 4. 终态检查 — Tool/job 失败立刻返回结构化失败 (Revision §3 不允许回退)
        switch (runResult.status()) {
            case REFUSED_NO_EVIDENCE:
                return ChatResult.of(StateHint.NO_RECALL,
                        reasonMessage(runResult), traceId);
            case REFUSED_PERMISSION:
                return ChatResult.of(StateHint.NO_RECALL,
                        "权限不足, 无法获取双方证据", traceId);
            case TOOL_FAILED:
                return ChatResult.of(StateHint.NO_RECALL,
                        "工具执行失败: " + safeReason(runResult), traceId);
            case TIMED_OUT:
                return ChatResult.of(StateHint.NO_RECALL,
                        "处理超时", traceId);
            case BUDGET_EXCEEDED:
                return ChatResult.of(StateHint.NO_RECALL,
                        "超出处理预算", traceId);
            case CANCELLED:
                return ChatResult.of(StateHint.NO_RECALL,
                        "已取消", traceId);
            case SYSTEM_FAILED:
                return ChatResult.of(StateHint.NO_RECALL,
                        "内部错误: " + safeReason(runResult), traceId);
            case READY_TO_ANSWER:
                // 继续 partition + composer
                break;
            default:
                return ChatResult.of(StateHint.NO_RECALL,
                        "未完成 Run: " + runResult.status(), traceId);
        }

        // 5. Evidence Partition
        Map<String, com.xxx.ragdoc.application.chat.comparison.ComparisonTarget> stepIdToTarget = new HashMap<>();
        stepIdToTarget.put(ComparisonPlanFactory.LEFT_STEP_ID, planResult.leftTarget());
        stepIdToTarget.put(ComparisonPlanFactory.RIGHT_STEP_ID, planResult.rightTarget());

        PartitionResult partitioned = partitioner.partition(
                runResult, principal.tenantId(), stepIdToTarget);
        if (!partitioned.valid()) {
            finalizer.markComposerFailed(runResult.runId(), runResult.finalRunVersion());
            return ChatResult.of(StateHint.NO_RECALL,
                    "证据分组失败: " + partitioned.failure(), traceId);
        }
        if (partitioned.evidenceSet().leftEvidence().isEmpty()
                || partitioned.evidenceSet().rightEvidence().isEmpty()) {
            log.info("comparison.executor.side_empty run={} left={} right={}",
                    runResult.runId(),
                    partitioned.evidenceSet().leftEvidence().size(),
                    partitioned.evidenceSet().rightEvidence().size());
            // Revision §7.3 — 任一侧空 → REFUSED_NO_EVIDENCE; 但 Run 已 READY_TO_ANSWER,
            // 不允许再写新终态 (会违反状态机); 直接返回 ChatResult NO_RECALL
            return ChatResult.of(StateHint.NO_RECALL,
                    sideMissingReason(partitioned.evidenceSet()), traceId);
        }

        // 6. Run ready version (Executor 写完 READY_TO_ANSWER 后记录的 finalRunVersion)
        long readyVersion = runResult.finalRunVersion();

        // 7. Composer — 单次 LLM 调用
        ComparisonAnswerComposer.ComparisonAnswer answer;
        try {
            answer = composer.compose(command.query(), partitioned.evidenceSet());
        } catch (Exception ex) {
            log.warn("comparison.composer_failed run={} err={}",
                    runResult.runId(), ex.toString());
            finalizer.markComposerFailed(runResult.runId(), readyVersion);
            return ChatResult.of(StateHint.NO_RECALL,
                    "答案生成失败", traceId);
        }

        // 8. Finalizer
        ComparisonRunFinalizer.FinalizeOutcome outcome =
                finalizer.finalizeAnswered(runResult.runId(), readyVersion);
        if (outcome instanceof ComparisonRunFinalizer.FinalizeOutcome.Answered) {
            return new ChatResult(
                    answer.text(),
                    buildCitations(partitioned.evidenceSet()),
                    StateHint.OK, traceId,
                    null /* verification disabled by default */,
                    null /* EvidenceSnapshot 由 ChatService 写; PR-6c 暂不重建 */);
        }
        if (outcome instanceof ComparisonRunFinalizer.FinalizeOutcome.Cancelled) {
            return ChatResult.of(StateHint.NO_RECALL, "已取消", traceId);
        }
        if (outcome instanceof ComparisonRunFinalizer.FinalizeOutcome.TimedOut) {
            return ChatResult.of(StateHint.NO_RECALL, "答案超时", traceId);
        }
        if (outcome instanceof ComparisonRunFinalizer.FinalizeOutcome.Conflict c) {
            return ChatResult.of(StateHint.NO_RECALL,
                    "结果冲突: " + (c.current() == null ? "missing" : c.current()), traceId);
        }
        return ChatResult.of(StateHint.NO_RECALL, "答案生成失败", traceId);
    }

    private static String safeReason(AgentRunResult r) {
        return r.terminalReasonCode() == null ? "" : r.terminalReasonCode();
    }

    private static String reasonMessage(AgentRunResult r) {
        return switch (safeReason(r)) {
            case "REQUIRED_EVIDENCE_MISSING", "NO_EVIDENCE" ->
                    "至少一方缺乏可引用的文档证据, 暂时无法给出比较答案";
            default -> "证据不足, 无法比较 (" + safeReason(r) + ")";
        };
    }

    private static String sideMissingReason(
            ComparisonEvidencePartitioner.ComparisonEvidenceSet set) {
        boolean l = set.leftEvidence().isEmpty();
        boolean r = set.rightEvidence().isEmpty();
        if (l && r) return "COMPARISON_BOTH_EVIDENCE_MISSING";
        if (l) return "COMPARISON_LEFT_EVIDENCE_MISSING";
        return "COMPARISON_RIGHT_EVIDENCE_MISSING";
    }

    private static List<ChatResult.Citation> buildCitations(
            ComparisonEvidencePartitioner.ComparisonEvidenceSet set) {
        List<ChatResult.Citation> out = new java.util.ArrayList<>();
        for (Evidence e : set.leftEvidence()) {
            out.add(toCitation(e));
        }
        for (Evidence e : set.rightEvidence()) {
            out.add(toCitation(e));
        }
        return out;
    }

    private static ChatResult.Citation toCitation(Evidence e) {
        return new ChatResult.Citation(
                e.chunkId(), e.documentId(), 0, snippetOf(e.content()),
                e.content(), List.of(), null);
    }

    private static String snippetOf(String content) {
        if (content == null) return "";
        return content.length() > 120 ? content.substring(0, 120) + "..." : content;
    }
}
