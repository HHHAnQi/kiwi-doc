package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * PR-6b.1 / EMS-PR6 §4: Agent 预算管理器 (纯逻辑, 无 DB / IO)。
 *
 * <p>职责:
 * <ol>
 *   <li>{@link #evaluate} — 在 Step RESERVED 之前联合判断 <b>usage + reservation + request &lt;= budget</b>;
 *       任何超限维度 → {@link BudgetDecision.Denied} (hard budget <b>终止整个 Run</b>, 即使 Step 是 optional,
 *       EMS-PR6 §7 / Revision §7)。
 *   <li>{@link #settle} — Tool / Replay / Dedup / Cancel 完成后, 把 reservation 结算到 usage。
 * </ol>
 *
 * <p>并发安全: 本类<b>不</b>读 DB, 不持状态; 真正并发安全靠
 * {@code AgentPersistenceCoordinator} 的 @Transactional REQUIRES_NEW 双 CAS (Revision §2)。
 *
 * <p>Budget / Usage / Reservation 三者语义 (Revision §3):
 *
 * <ul>
 *   <li>{@link AgentBudget} — 最大允许量 (服务端构造, 不可扩大)
 *   <li>{@link AgentBudgetReservation} — 已预留、未结算
 *   <li>{@link AgentUsage} — 已真实结算发生
 * </ul>
 */
@Component
public class AgentBudgetManager {

    /**
     * 联合预算判断。返回 Allowed(新 reservation) 或 Denied(dimension)。
     *
     * <p>判断公式 (任一维度突破即 Denied):
     *
     * <pre>
     *   usage.usedSteps       + reservation.reservedSteps       + req.requiredSteps       <= budget.maxSteps
     *   usage.usedToolCalls   + reservation.reservedToolCalls   + req.requiredToolCalls   <= budget.maxToolCalls
     *   usage.usedInputTokens + reservation.reservedInputTokens + req.requiredInputTokens <= budget.maxInputTokens       (maxInputTokens > 0 时)
     *   usage.usedOutputTokens+ reservation.reservedOutputTokens+ req.requiredOutputTokens<= budget.maxOutputTokens      (maxOutputTokens> 0 时)
     *   usage.usedTotalTokens + reservation.reservedInputTokens + reservation.reservedOutputTokens + req... <= budget.maxTotalTokens (maxTotalTokens>0)
     *   usage.estimatedCost   + reservation.reservedEstimatedCost + req.requiredEstimatedCost  <= budget.maxEstimatedCost (maxEstimatedCost > 0 时)
     * </pre>
     *
     * <p>{@code maxExecutionMillis} 不在 evaluate 中检查 —— 它由 Executor 通过 Clock 显式 deadline 比较,
     * 因为 elapsedMillis 需要服务端现在时刻。
     */
    public BudgetDecision evaluate(
            AgentBudget budget,
            AgentUsage usage,
            AgentBudgetReservation reservation,
            ReservationRequest request) {
        if (budget == null) throw new IllegalArgumentException("budget");
        if (usage == null) usage = AgentUsage.zero();
        if (reservation == null) reservation = AgentBudgetReservation.zero();
        if (request == null) throw new IllegalArgumentException("request");

        long steps = (long) usage.usedSteps() + reservation.reservedSteps() + request.requiredSteps();
        if (steps > budget.maxSteps()) {
            return new BudgetDecision.Denied(BudgetDimension.MAX_STEPS,
                    "steps: " + steps + " > " + budget.maxSteps());
        }
        long toolCalls = (long) usage.usedToolCalls()
                + reservation.reservedToolCalls() + request.requiredToolCalls();
        if (toolCalls > budget.maxToolCalls()) {
            return new BudgetDecision.Denied(BudgetDimension.MAX_TOOL_CALLS,
                    "toolCalls: " + toolCalls + " > " + budget.maxToolCalls());
        }
        if (budget.maxInputTokens() > 0) {
            long inTok = usage.usedInputTokens() + reservation.reservedInputTokens() + request.requiredInputTokens();
            if (inTok > budget.maxInputTokens()) {
                return new BudgetDecision.Denied(BudgetDimension.INPUT_TOKENS,
                        "inputTokens: " + inTok + " > " + budget.maxInputTokens());
            }
        }
        if (budget.maxOutputTokens() > 0) {
            long outTok = usage.usedOutputTokens() + reservation.reservedOutputTokens() + request.requiredOutputTokens();
            if (outTok > budget.maxOutputTokens()) {
                return new BudgetDecision.Denied(BudgetDimension.OUTPUT_TOKENS,
                        "outputTokens: " + outTok + " > " + budget.maxOutputTokens());
            }
        }
        if (budget.maxTotalTokens() > 0) {
            long totTok = usage.usedTotalTokens()
                    + reservation.reservedInputTokens() + reservation.reservedOutputTokens()
                    + request.requiredInputTokens() + request.requiredOutputTokens();
            if (totTok > budget.maxTotalTokens()) {
                return new BudgetDecision.Denied(BudgetDimension.TOTAL_TOKENS,
                        "totalTokens: " + totTok + " > " + budget.maxTotalTokens());
            }
        }
        if (budget.maxEstimatedCost().signum() > 0) {
            BigDecimal cost = usage.estimatedCost()
                    .add(reservation.reservedEstimatedCost())
                    .add(request.requiredEstimatedCost());
            if (cost.compareTo(budget.maxEstimatedCost()) > 0) {
                return new BudgetDecision.Denied(BudgetDimension.COST,
                        "estimatedCost: " + cost + " > " + budget.maxEstimatedCost());
            }
        }

        // 通过: 新 reservation = 旧 reservation + 本次 req
        AgentBudgetReservation next = new AgentBudgetReservation(
                reservation.reservedSteps() + request.requiredSteps(),
                reservation.reservedToolCalls() + request.requiredToolCalls(),
                reservation.reservedPlannerCalls(),
                reservation.reservedInputTokens() + request.requiredInputTokens(),
                reservation.reservedOutputTokens() + request.requiredOutputTokens(),
                reservation.reservedEstimatedCost().add(request.requiredEstimatedCost()));
        return new BudgetDecision.Allowed(next);
    }

    /**
     * 结算: 把本次 Step 的 reservation 转 usage (REAL_TOOL), 或仅释放 (CANCEL), 或仅计 logical step (REPLAY/DEDUP)。
     *
     * @return 包含 (newUsage, newReservation) — 在 Coordinator 的 settle 事务内写库
     */
    public SettleResult settle(
            AgentUsage usage,
            AgentBudgetReservation reservation,
            StepSettlement settlement) {
        if (usage == null) usage = AgentUsage.zero();
        if (reservation == null) reservation = AgentBudgetReservation.zero();
        if (settlement == null) throw new IllegalArgumentException("settlement");

        long inputTok = settlement.inputTokens().isPresent() ? settlement.inputTokens().getAsLong() : 0;
        long outputTok = settlement.outputTokens().isPresent() ? settlement.outputTokens().getAsLong() : 0;
        BigDecimal cost = settlement.estimatedCost() == null ? BigDecimal.ZERO : settlement.estimatedCost();

        return switch (settlement.outcome()) {
            case REAL_TOOL -> {
                AgentUsage nextUsage = new AgentUsage(
                        usage.usedSteps() + 1,
                        usage.usedToolCalls() + 1,
                        usage.usedPlannerCalls(),
                        usage.usedInputTokens() + inputTok,
                        usage.usedOutputTokens() + outputTok,
                        usage.usedTotalTokens() + inputTok + outputTok,
                        usage.elapsedMillis(),
                        usage.estimatedCost().add(cost));
                AgentBudgetReservation nextRes = releaseOne(reservation);
                yield new SettleResult(nextUsage, nextRes);
            }
            case LOGICAL_STEP_REPLAY, LOGICAL_STEP_DEDUP -> {
                // 不计真实 Tool call / 不计外部成本; 计一 logical step
                AgentUsage nextUsage = new AgentUsage(
                        usage.usedSteps() + 1,
                        usage.usedToolCalls(),
                        usage.usedPlannerCalls(),
                        usage.usedInputTokens(),
                        usage.usedOutputTokens(),
                        usage.usedTotalTokens(),
                        usage.elapsedMillis(),
                        usage.estimatedCost());
                AgentBudgetReservation nextRes = releaseOne(reservation);
                yield new SettleResult(nextUsage, nextRes);
            }
            case CANCELLED_BEFORE_TOOL -> new SettleResult(usage, releaseOne(reservation));
            case SKIPPED_BUDGET, SKIPPED_DEPENDENCY -> new SettleResult(usage, reservation);
        };
    }

    /** 释放 1 个预留 step + 1 个预留 tool call (向下取 floor=0)。 */
    private static AgentBudgetReservation releaseOne(AgentBudgetReservation r) {
        return new AgentBudgetReservation(
                Math.max(0, r.reservedSteps() - 1),
                Math.max(0, r.reservedToolCalls() - 1),
                r.reservedPlannerCalls(),
                r.reservedInputTokens(),
                r.reservedOutputTokens(),
                r.reservedEstimatedCost());
    }

    /** settle 结果 (新增 Pair 字段以避免 java 多返回值问题)。 */
    public record SettleResult(AgentUsage newUsage, AgentBudgetReservation newReservation) {
        public SettleResult {
            if (newUsage == null) newUsage = AgentUsage.zero();
            if (newReservation == null) newReservation = AgentBudgetReservation.zero();
        }
    }
}
