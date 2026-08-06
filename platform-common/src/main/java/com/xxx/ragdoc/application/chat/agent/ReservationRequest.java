package com.xxx.ragdoc.application.chat.agent;

import java.math.BigDecimal;

/**
 * PR-6b / EMS-PR6 §4.3: 单次预留申请。
 *
 * <p>BudgetManager 在 Step RESERVED 之前传入本次申请量; 未知维度必须填 0 (LIVE 调用前不知道 token 真实值),
 * 不得伪造精确值。
 *
 * <p>requiredSteps / requiredToolCalls 用于占位去重 / replay 时分别为 (1, 0)。
 */
public record ReservationRequest(
        int requiredSteps,
        int requiredToolCalls,
        long requiredInputTokens,
        long requiredOutputTokens,
        BigDecimal requiredEstimatedCost) {

    public ReservationRequest {
        if (requiredSteps < 0) requiredSteps = 0;
        if (requiredToolCalls < 0) requiredToolCalls = 0;
        if (requiredInputTokens < 0) requiredInputTokens = 0;
        if (requiredOutputTokens < 0) requiredOutputTokens = 0;
        requiredEstimatedCost = requiredEstimatedCost == null ? BigDecimal.ZERO : requiredEstimatedCost;
    }

    /**
     * 真实 LIVE / RECORD Tool 调用前的保守预留: 占 1 step + 1 真实 tool call; token / cost 在执行前未知,
     * 显式填 0 (settle 时回填真实值)。
     */
    public static ReservationRequest forRealToolCall() {
        return new ReservationRequest(1, 1, 0, 0, BigDecimal.ZERO);
    }

    /** DEDUP / REPLAY Step 不计真实 Tool call, 仅占 1 logical step。 */
    public static ReservationRequest forLogicalStep() {
        return new ReservationRequest(1, 0, 0, 0, BigDecimal.ZERO);
    }
}
