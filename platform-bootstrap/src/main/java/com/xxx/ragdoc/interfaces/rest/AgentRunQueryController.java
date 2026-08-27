package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.agent.AgentRunRepository;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1(ADR-0012 §7 Phase1): Agent run 只读查询端点 — 评测与审计必需。
 *
 * <p>权限: 登录用户仅可查本租户的 run(跨租户 404 防枚举, 与文档守门同语义)。
 * resume(续跑)属 Phase 2, 本端点只读。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "Agentic 执行审计")
public class AgentRunQueryController {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;

    /** 单个 run 详情 + 全部 steps(按执行顺序)。 */
    @GetMapping("/runs/{runId}")
    @Operation(summary = "Agent run 详情(含 steps)", description = "只读审计; 跨租户 404")
    public AgentRunDetailResponse getRun(@PathVariable String runId) {
        var run =
                runRepository
                        .findByRunId(runId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.TOOL_NOT_FOUND, "run 不存在: " + runId));
        String tenant = AuthContext.currentPrincipal().tenantId();
        if (!tenant.equals(run.tenantId())) {
            // 防枚举: 跨租户与不存在同返 404
            throw new NotFoundException(ErrorCode.TOOL_NOT_FOUND, "run 不存在: " + runId);
        }
        List<StepView> steps =
                stepRepository.findByRunId(runId).stream().map(StepView::from).toList();
        return AgentRunDetailResponse.from(run, steps);
    }

    // ─── DTO(view 层, 不暴露 planJson/usage 原始 JSON) ────────────────

    public record AgentRunDetailResponse(
            String runId,
            String requestId,
            String strategy,
            String status,
            String terminalReasonCode,
            String planId,
            String plannerVersion,
            int evidenceCount,
            int stepCount,
            String createdAt,
            String updatedAt,
            List<StepView> steps) {

        static AgentRunDetailResponse from(
                com.xxx.ragdoc.application.chat.agent.AgentRunRecord run, List<StepView> steps) {
            return new AgentRunDetailResponse(
                    run.runId(),
                    run.requestId(),
                    run.strategy(),
                    run.status() == null ? null : run.status().name(),
                    run.terminalReasonCode(),
                    run.planId(),
                    // P0-2(评测隔离): planner 实际来源(model-llm-v1 / rule-fallback-v1:REASON /
                    // rule-based-v1) — 评测 runner 据此逐样本判定 planner_source, 防止降级样本
                    // 静默混入 LLM Planner 实验组。
                    run.routerVersion(),
                    run.evidenceCount(),
                    steps.size(),
                    run.createdAt() == null ? null : run.createdAt().toString(),
                    run.updatedAt() == null ? null : run.updatedAt().toString(),
                    steps);
        }
    }

    public record StepView(
            String stepId,
            int sequence,
            String toolName,
            String status,
            int resultCount,
            Long latencyMs,
            String errorCode,
            boolean replayed,
            boolean deduplicated) {

        static StepView from(com.xxx.ragdoc.application.chat.agent.AgentStepRecord s) {
            return new StepView(
                    s.stepId(),
                    s.stepSequence(),
                    s.toolName(),
                    s.status() == null ? null : s.status().name(),
                    s.resultCount(),
                    s.latencyMs(),
                    s.errorCode(),
                    s.replayed(),
                    s.deduplicated());
        }
    }
}
