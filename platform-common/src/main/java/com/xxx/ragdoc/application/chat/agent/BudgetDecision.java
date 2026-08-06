package com.xxx.ragdoc.application.chat.agent;

import java.util.Optional;

/**
 * PR-6b / EMS-PR6 §4.3: BudgetManager.evaluate 输出。
 *
 * <p>sealed 接口 — 只有两种结果: {@link Allowed} / {@link Denied}。
 *
 * <p><b>hard budget</b>: Denied 即终止整个 Run (即使是 optional Step 也跳; 后续 Step 同样超预算)。
 */
public sealed interface BudgetDecision permits BudgetDecision.Allowed, BudgetDecision.Denied {

    /** 允许预留; 返回预留后新的 reservation (usage 不变)。 */
    record Allowed(AgentBudgetReservation newReservation) implements BudgetDecision {
        public Allowed {
            if (newReservation == null) throw new IllegalArgumentException("newReservation");
        }
    }

    /** 拒绝 (突破预算); Executor 据此转 Run=BUDGET_EXCEEDED + Step=SKIPPED_BUDGET。 */
    record Denied(BudgetDimension dimension, String safeMessage) implements BudgetDecision {
        public Denied {
            if (dimension == null) dimension = BudgetDimension.MAX_STEPS;
            if (safeMessage == null) safeMessage = "budget exceeded: " + dimension;
        }
    }

    default Optional<BudgetDimension> deniedDimension() {
        return this instanceof Denied d ? Optional.of(d.dimension()) : Optional.empty();
    }
}
