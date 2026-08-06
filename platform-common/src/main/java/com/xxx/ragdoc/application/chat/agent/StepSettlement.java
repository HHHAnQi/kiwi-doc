package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;
import java.util.OptionalLong;

/**
 * PR-6b / EMS-PR6 §4.4: 单 Step 执行结果的结算类别 (用于 settle 时的 usage / reservation 计算)。
 *
 * <p>Executor 完成 step 后据此决定 BudgetManager.settle(...) 走哪条规则:
 *
 * <ul>
 *   <li>{@link #REAL_TOOL} — 真实 LIVE/RECORD Tool 调用 (含 TIMEOUT/FAILED_RETRYABLE/FAILED_TERMINAL);
 *       usedSteps+1, usedToolCalls+1, reservation 释放 1 step + 1 call。
 *   <li>{@link #LOGICAL_STEP_REPLAY} — REPLAY 命中 fixture; usedSteps+1, usedToolCalls 不变, 不计外部成本,
 *       标 replayed=true。
 *   <li>{@link #LOGICAL_STEP_DEDUP} — dedup cache 命中; usedSteps+1, usedToolCalls 不变, 标 deduplicated=true。
 *   <li>{@link #CANCELLED_BEFORE_TOOL} — Step 进入 RESERVED 后被取消 (Tool 未真实启动);
 *       release reservation, usedSteps+0 (按 EMS-PR6 §4.5 推荐: RESERVED-only 不计 logical step)。
 *   <li>{@link #SKIPPED_BUDGET} — 预算拒绝 (从未预留)。
 *   <li>{@link #SKIPPED_DEPENDENCY} — 依赖未满足, 转 FAILED_TERMINAL errorCode=DEPENDENCY_NOT_SATISFIED, 不计费。
 * </ul>
 */
public record StepSettlement(
        Outcome outcome,
        AgentStepStatus terminalStepStatus,
        String errorCode,
        boolean replayed,
        boolean deduplicated,
        /** REAL_TOOL 时真实 token (Tool 返回给定的近似值); 未知填 OptionalLong.empty()。 */
        OptionalLong inputTokens,
        OptionalLong outputTokens,
        BigDecimal estimatedCost) {

    public enum Outcome {
        REAL_TOOL,
        LOGICAL_STEP_REPLAY,
        LOGICAL_STEP_DEDUP,
        CANCELLED_BEFORE_TOOL,
        SKIPPED_BUDGET,
        SKIPPED_DEPENDENCY
    }

    public StepSettlement {
        if (outcome == null) throw new IllegalArgumentException("outcome");
        if (terminalStepStatus == null) throw new IllegalArgumentException("terminalStepStatus");
        if (errorCode == null) errorCode = "";
        if (inputTokens == null) inputTokens = OptionalLong.empty();
        if (outputTokens == null) outputTokens = OptionalLong.empty();
        estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        if (!terminalStepStatus.isTerminal()) {
            throw new IllegalArgumentException(
                    "StepSettlement.terminalStepStatus 必须是终态: " + terminalStepStatus);
        }
    }

    public static StepSettlement realTool(AgentStepStatus terminal, String errorCode, long inputTokens, long outputTokens,
                                          BigDecimal cost) {
        return new StepSettlement(
                Outcome.REAL_TOOL, terminal, errorCode, false, false,
                OptionalLong.of(inputTokens), OptionalLong.of(outputTokens), cost);
    }

    public static StepSettlement replay(AgentStepStatus terminal, String errorCode) {
        return new StepSettlement(
                Outcome.LOGICAL_STEP_REPLAY, terminal, errorCode, true, false,
                OptionalLong.empty(), OptionalLong.empty(), BigDecimal.ZERO);
    }

    public static StepSettlement dedup(AgentStepStatus terminal, String errorCode) {
        return new StepSettlement(
                Outcome.LOGICAL_STEP_DEDUP, terminal, errorCode, false, true,
                OptionalLong.empty(), OptionalLong.empty(), BigDecimal.ZERO);
    }

    public static StepSettlement cancelledBeforeTool() {
        return new StepSettlement(
                Outcome.CANCELLED_BEFORE_TOOL, AgentStepStatus.CANCELLED, "", false, false,
                OptionalLong.empty(), OptionalLong.empty(), BigDecimal.ZERO);
    }

    public static StepSettlement skippedBudget() {
        return new StepSettlement(
                Outcome.SKIPPED_BUDGET, AgentStepStatus.SKIPPED_BUDGET, "BUDGET_EXCEEDED",
                false, false, OptionalLong.empty(), OptionalLong.empty(), BigDecimal.ZERO);
    }

    public static StepSettlement skippedDependency() {
        return new StepSettlement(
                Outcome.SKIPPED_DEPENDENCY, AgentStepStatus.FAILED_TERMINAL, "DEPENDENCY_NOT_SATISFIED",
                false, false, OptionalLong.empty(), OptionalLong.empty(), BigDecimal.ZERO);
    }
}
