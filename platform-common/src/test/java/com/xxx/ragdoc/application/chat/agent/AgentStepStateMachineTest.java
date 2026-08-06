package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-6b / EMS-PR6 §5: {@link AgentStepStateMachine} 合法/非法转换表测试。
 *
 * <p>覆盖主线 / 跳过 / 取消 / 收敛 / 终态保护 / from=null 全路径。
 */
@DisplayName("AgentStepStateMachine - PR-6b Step 合法转换表 + 终态保护")
class AgentStepStateMachineTest {

    @Nested
    @DisplayName("主线转换")
    class MainLine {

        @Test
        @DisplayName("PENDING → RESERVED 合法")
        void pendingToReserved() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.PENDING, AgentStepStatus.RESERVED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RESERVED → RUNNING 合法")
        void reservedToRunning() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RESERVED, AgentStepStatus.RUNNING))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → SUCCEEDED 合法")
        void runningToSucceeded() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.SUCCEEDED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → EMPTY 合法 (检索 0 命中)")
        void runningToEmpty() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.EMPTY))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("失败收敛")
    class FailureConvergence {

        @Test
        @DisplayName("RUNNING → FAILED_RETRYABLE → FAILED_TERMINAL 合法")
        void retryableThenTerminal() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.FAILED_RETRYABLE))
                    .doesNotThrowAnyException();
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.FAILED_RETRYABLE, AgentStepStatus.FAILED_TERMINAL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → FAILED_TERMINAL 直接跳过 retryable 合法")
        void runningToFailedTerminal() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.FAILED_TERMINAL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → PERMISSION_DENIED 合法")
        void runningToPermissionDenied() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.PERMISSION_DENIED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → TIMED_OUT 合法")
        void runningToTimedOut() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.TIMED_OUT))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("跳过路径")
    class SkipPaths {

        @Test
        @DisplayName("PENDING → SKIPPED_BUDGET 合法 (从未预留, hard budget 拒绝)")
        void pendingToSkippedBudget() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.PENDING, AgentStepStatus.SKIPPED_BUDGET))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PENDING → SKIPPED_DUPLICATE 合法")
        void pendingToSkippedDuplicate() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.PENDING, AgentStepStatus.SKIPPED_DUPLICATE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RESERVED → SKIPPED_DUPLICATE 合法")
        void reservedToSkippedDuplicate() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RESERVED, AgentStepStatus.SKIPPED_DUPLICATE))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("取消路径")
    class CancelPaths {

        @Test
        @DisplayName("PENDING → CANCELLED 合法")
        void pendingToCancelled() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.PENDING, AgentStepStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RESERVED → CANCELLED 合法 (Cancel-before-tool 释放_reservation)")
        void reservedToCancelled() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RESERVED, AgentStepStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RUNNING → CANCELLED 合法")
        void runningToCancelled() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FAILED_RETRYABLE → CANCELLED 合法 (终态收敛)")
        void retryableToCancelled() {
            assertThatCode(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.FAILED_RETRYABLE, AgentStepStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("非法转换 / 终态保护")
    class IllegalAndTerminal {

        @Test
        @DisplayName("PENDING → SUCCEEDED 非法 (跳过 RESERVED/RUNNING)")
        void pendingToSucceededIllegal() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.PENDING, AgentStepStatus.SUCCEEDED))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class);
        }

        @Test
        @DisplayName("RESERVED → SUCCEEDED 非法 (必须先 RUNNING)")
        void reservedToSucceededIllegal() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RESERVED, AgentStepStatus.SUCCEEDED))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class);
        }

        @Test
        @DisplayName("REJECTED 路径: RUNNING → PENDING 非法 (状态不能倒退)")
        void runningToPendingIllegal() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.RUNNING, AgentStepStatus.PENDING))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class);
        }

        @Test
        @DisplayName("SUCCEEDED 是终态, 任何出口非法")
        void succeededTerminal() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.SUCCEEDED, AgentStepStatus.CANCELLED))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class)
                    .hasMessageContaining("终态");
        }

        @Test
        @DisplayName("EMPTY 是终态, 不允许 RUNNING")
        void emptyTerminal() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(
                    AgentStepStatus.EMPTY, AgentStepStatus.RUNNING))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class);
        }

        @Test
        @DisplayName("SKIPPED_BUDGET / SKIPPED_DUPLICATE / CANCELLED / FAILED_TERMINAL / PERMISSION_DENIED / TIMED_OUT 全部是终态")
        void allTerminalStates() {
            AgentStepStatus[] terminal = {
                AgentStepStatus.SKIPPED_BUDGET, AgentStepStatus.SKIPPED_DUPLICATE, AgentStepStatus.CANCELLED,
                AgentStepStatus.FAILED_TERMINAL, AgentStepStatus.PERMISSION_DENIED, AgentStepStatus.TIMED_OUT
            };
            for (AgentStepStatus t : terminal) {
                assertThat(t.isTerminal()).as("%s 应是终态", t).isTrue();
                assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(t, AgentStepStatus.SUCCEEDED))
                        .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class);
            }
        }

        @Test
        @DisplayName("from=null 直接抛 (防御编程)")
        void nullFrom() {
            assertThatThrownBy(() -> AgentStepStateMachine.checkLegal(null, AgentStepStatus.RESERVED))
                    .isInstanceOf(AgentStepStateMachine.IllegalStepTransitionException.class)
                    .hasMessageContaining("from=null");
        }
    }
}
