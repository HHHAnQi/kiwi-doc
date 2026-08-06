package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-6b.1 / EMS-PR6 §4: {@link AgentBudgetManager} 单测 (纯逻辑, 不依赖 DB / Coordinator)。
 */
@DisplayName("AgentBudgetManager - PR-6b evaluate + settle 四类规则")
class AgentBudgetManagerTest {

    private AgentBudgetManager mgr;

    @BeforeEach
    void setup() {
        mgr = new AgentBudgetManager();
    }

    @Nested
    @DisplayName("evaluate: usage + reservation + request 联合判断")
    class Evaluate {

        @Test
        @DisplayName("正常预留通过, newReservation 是 +1 step +1 call")
        void normalReserveAllowed() {
            BudgetDecision d = mgr.evaluate(
                    AgentBudget.pr6Default(), AgentUsage.zero(),
                    AgentBudgetReservation.zero(), ReservationRequest.forRealToolCall());
            assertThat(d).isInstanceOf(BudgetDecision.Allowed.class);
            BudgetDecision.Allowed a = (BudgetDecision.Allowed) d;
            assertThat(a.newReservation().reservedSteps()).isEqualTo(1);
            assertThat(a.newReservation().reservedToolCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("maxSteps 突破 (usage 1 + reservation 1 + req 1 = 3 ≤ 3 通过; 4 > 3 拒)")
        void maxStepsBreakDeny() {
            // budget maxSteps=3
            AgentUsage usage = AgentUsage.zero().incStep(); // usedSteps=1
            AgentBudgetReservation res = new AgentBudgetReservation(2, 1, 0, 0, 0, BigDecimal.ZERO);
            // usage1 + res2 + req1 = 4 > 3 → 拒
            BudgetDecision d = mgr.evaluate(
                    AgentBudget.pr6Default(), usage, res, ReservationRequest.forRealToolCall());
            assertThat(d).isInstanceOf(BudgetDecision.Denied.class);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.MAX_STEPS);
        }

        @Test
        @DisplayName("maxToolCalls 突破")
        void maxToolCallsBreakDeny() {
            // budget maxToolCalls=5; usage3 + res2 + req1 = 6
            AgentUsage usage = new AgentUsage(
                    0, 3, 0, 0, 0, 0, 0, BigDecimal.ZERO);
            AgentBudgetReservation res = new AgentBudgetReservation(0, 2, 0, 0, 0, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(
                    AgentBudget.pr6Default(), usage, res, ReservationRequest.forRealToolCall());
            assertThat(d).isInstanceOf(BudgetDecision.Denied.class);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.MAX_TOOL_CALLS);
        }

        @Test
        @DisplayName("usage + reservation 联合判断: 只看 usage 会通过, 联合判断必拒 (Revision §3 关键)")
        void combinedCheckNotUsageOnly() {
            // budget maxSteps=3; usage0 + res3 + req1 = 4 > 3
            AgentBudgetReservation res = new AgentBudgetReservation(3, 0, 0, 0, 0, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(
                    AgentBudget.pr6Default(), AgentUsage.zero(), res, ReservationRequest.forLogicalStep());
            assertThat(d).isInstanceOf(BudgetDecision.Denied.class);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.MAX_STEPS);
        }

        @Test
        @DisplayName("maxInputTokens 上限突破 (>0 时才检查)")
        void inputTokenLimitBreak() {
            AgentBudget budget = new AgentBudget(
                    10, 10, 0, 0, 30_000L,
                    1000, 0, 0, BigDecimal.ZERO);
            // usage500 + res300 + req300 = 1100 > 1000
            AgentUsage u = new AgentUsage(0, 0, 0, 500, 0, 0, 0, BigDecimal.ZERO);
            AgentBudgetReservation r = new AgentBudgetReservation(0, 0, 0, 300, 0, BigDecimal.ZERO);
            ReservationRequest req = new ReservationRequest(1, 0, 300, 0, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(budget, u, r, req);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.INPUT_TOKENS);
        }

        @Test
        @DisplayName("maxOutputTokens 突破")
        void outputTokenLimitBreak() {
            AgentBudget budget = new AgentBudget(
                    10, 10, 0, 0, 30_000L,
                    0, 1000, 0, BigDecimal.ZERO);
            AgentUsage u = new AgentUsage(0, 0, 0, 0, 600, 0, 0, BigDecimal.ZERO);
            AgentBudgetReservation r = new AgentBudgetReservation(0, 0, 0, 0, 500, BigDecimal.ZERO);
            ReservationRequest req = new ReservationRequest(1, 0, 0, 200, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(budget, u, r, req);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.OUTPUT_TOKENS);
        }

        @Test
        @DisplayName("maxTotalTokens 突破 (Token = in+out 总和)")
        void totalTokenLimitBreak() {
            AgentBudget budget = new AgentBudget(
                    10, 10, 0, 0, 30_000L,
                    0, 0, 1000, BigDecimal.ZERO);
            AgentUsage u = new AgentUsage(0, 0, 0, 200, 200, 400, 0, BigDecimal.ZERO);
            // res400 in + req400 out = 800; total 400+800 = 1200 > 1000
            AgentBudgetReservation r = new AgentBudgetReservation(0, 0, 0, 400, 0, BigDecimal.ZERO);
            ReservationRequest req = new ReservationRequest(1, 0, 0, 400, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(budget, u, r, req);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.TOTAL_TOKENS);
        }

        @Test
        @DisplayName("maxEstimatedCost 突破")
        void costLimitBreak() {
            AgentBudget budget = new AgentBudget(
                    10, 10, 0, 0, 30_000L,
                    0, 0, 0, new BigDecimal("0.50"));
            AgentUsage u = new AgentUsage(0, 0, 0, 0, 0, 0, 0, new BigDecimal("0.30"));
            AgentBudgetReservation r = new AgentBudgetReservation(0, 0, 0, 0, 0, new BigDecimal("0.10"));
            ReservationRequest req = new ReservationRequest(1, 0, 0, 0, new BigDecimal("0.20"));
            BudgetDecision d = mgr.evaluate(budget, u, r, req);
            assertThat(((BudgetDecision.Denied) d).dimension()).isEqualTo(BudgetDimension.COST);
        }

        @Test
        @DisplayName("maxInputTokens=0 时不检查 (无限制语义)")
        void zeroTokenLimitSkipsCheck() {
            AgentBudget budget = AgentBudget.pr6Default(); // maxInputTokens=0
            ReservationRequest req = new ReservationRequest(1, 1, 999_999_999L, 0, BigDecimal.ZERO);
            BudgetDecision d = mgr.evaluate(
                    budget, AgentUsage.zero(), AgentBudgetReservation.zero(), req);
            assertThat(d).isInstanceOf(BudgetDecision.Allowed.class);
        }

        @Test
        @DisplayName("null 参数 fail-closed")
        void nullArgsRejected() {
            assertThatThrownBy(
                    () -> mgr.evaluate(null, AgentUsage.zero(), AgentBudgetReservation.zero(),
                            ReservationRequest.forRealToolCall()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                    () -> mgr.evaluate(AgentBudget.pr6Default(), AgentUsage.zero(),
                            AgentBudgetReservation.zero(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("settle: 四类结算规则")
    class Settle {

        private AgentUsage usage1 = AgentUsage.zero().incStep().incRealToolCall();

        @Test
        @DisplayName("REAL_TOOL: reserved-1, usedSteps+1, usedToolCalls+1, 真实 token/cost 累计")
        void realToolSettle() {
            AgentBudgetReservation res = new AgentBudgetReservation(1, 1, 0, 0, 0, BigDecimal.ZERO);
            StepSettlement s = StepSettlement.realTool(
                    AgentStepStatus.SUCCEEDED, "", 200, 80, new BigDecimal("0.05"));
            var r = mgr.settle(usage1, res, s);
            assertThat(r.newReservation().reservedSteps()).isZero();
            assertThat(r.newReservation().reservedToolCalls()).isZero();
            assertThat(r.newUsage().usedSteps()).isEqualTo(2);
            assertThat(r.newUsage().usedToolCalls()).isEqualTo(2);
            assertThat(r.newUsage().usedInputTokens()).isEqualTo(200);
            assertThat(r.newUsage().usedOutputTokens()).isEqualTo(80);
            assertThat(r.newUsage().estimatedCost()).isEqualByComparingTo("0.05");
        }

        @Test
        @DisplayName("REPLAY: reserved-1, usedSteps+1, usedToolCalls 不变 (不计真实调用)")
        void replaySettle() {
            AgentBudgetReservation res = new AgentBudgetReservation(1, 0, 0, 0, 0, BigDecimal.ZERO);
            StepSettlement s = StepSettlement.replay(AgentStepStatus.SUCCEEDED, "");
            var r = mgr.settle(usage1, res, s);
            assertThat(r.newReservation().reservedSteps()).isZero();
            assertThat(r.newUsage().usedSteps()).isEqualTo(2);
            assertThat(r.newUsage().usedToolCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("DEDUP: reserved-1, usedSteps+1, usedToolCalls 不变")
        void dedupSettle() {
            AgentBudgetReservation res = new AgentBudgetReservation(1, 0, 0, 0, 0, BigDecimal.ZERO);
            StepSettlement s = StepSettlement.dedup(AgentStepStatus.SKIPPED_DUPLICATE, "");
            var r = mgr.settle(usage1, res, s);
            assertThat(r.newUsage().usedSteps()).isEqualTo(2);
            assertThat(r.newUsage().usedToolCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("CANCELLED_BEFORE_TOOL: 仅释放 reservation, usedSteps 不增 (Revision §4.5)")
        void cancelReleasesReservationOnly() {
            AgentBudgetReservation res = new AgentBudgetReservation(1, 1, 0, 0, 0, BigDecimal.ZERO);
            StepSettlement s = StepSettlement.cancelledBeforeTool();
            var r = mgr.settle(usage1, res, s);
            assertThat(r.newUsage().usedSteps()).isEqualTo(1);
            assertThat(r.newReservation().reservedSteps()).isZero();
            assertThat(r.newReservation().reservedToolCalls()).isZero();
        }

        @Test
        @DisplayName("SKIPPED_BUDGET: 不动 usage/reservation (从未预留)")
        void skippedBudgetKeepsState() {
            StepSettlement s = StepSettlement.skippedBudget();
            var r = mgr.settle(usage1, AgentBudgetReservation.zero(), s);
            assertThat(r.newUsage().usedSteps()).isEqualTo(1);
            assertThat(r.newReservation().reservedSteps()).isZero();
        }

        @Test
        @DisplayName("结算后 reservation 必须归零 (Revision §14: settle 后 reservation 归零)")
        void settleLeavesReservationZero() {
            AgentBudgetReservation res = new AgentBudgetReservation(1, 1, 0, 0, 0, BigDecimal.ZERO);
            StepSettlement s = StepSettlement.realTool(
                    AgentStepStatus.SUCCEEDED, "", 0, 0, BigDecimal.ZERO);
            var r = mgr.settle(usage1, res, s);
            assertThat(r.newReservation().reservedSteps()).isZero();
            assertThat(r.newReservation().reservedToolCalls()).isZero();
        }
    }
}
