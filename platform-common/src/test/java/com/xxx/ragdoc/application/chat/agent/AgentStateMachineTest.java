package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PR-6a.1: AgentStateMachine 合法转换 + 终态保护 + 不可逆 invariant 单测。 */
@DisplayName("AgentStateMachine - PR-6a.1")
class AgentStateMachineTest {

    @Nested
    @DisplayName("主线")
    class Mainline {

        @Test
        @DisplayName("RECEIVED → ROUTED 合法")
        void receivedToRouted() {
            AgentStateMachine.checkLegal(AgentRunStatus.RECEIVED, AgentRunStatus.ROUTED);
        }

        @Test
        @DisplayName("ROUTED → PLANNED 合法")
        void routedToPlanned() {
            AgentStateMachine.checkLegal(AgentRunStatus.ROUTED, AgentRunStatus.PLANNED);
        }

        @Test
        @DisplayName("PLANNED → EXECUTING 合法")
        void plannedToExecuting() {
            AgentStateMachine.checkLegal(AgentRunStatus.PLANNED, AgentRunStatus.EXECUTING);
        }

        @Test
        @DisplayName("EXECUTING → READY_TO_ANSWER 合法")
        void executingToReady() {
            AgentStateMachine.checkLegal(AgentRunStatus.EXECUTING, AgentRunStatus.READY_TO_ANSWER);
        }

        @Test
        @DisplayName("READY_TO_ANSWER → ANSWERED 合法")
        void readyToAnswered() {
            AgentStateMachine.checkLegal(AgentRunStatus.READY_TO_ANSWER, AgentRunStatus.ANSWERED);
        }

        @Test
        @DisplayName("READY_TO_ANSWER → REFUSED_NO_EVIDENCE 合法")
        void readyToNoEvidence() {
            AgentStateMachine.checkLegal(
                    AgentRunStatus.READY_TO_ANSWER, AgentRunStatus.REFUSED_NO_EVIDENCE);
        }
    }

    @Nested
    @DisplayName("失败转换 (任意非终态 → failure 终态)")
    class Failures {

        @Test
        @DisplayName("EXECUTING → TOOL_FAILED 合法")
        void executingToToolFailed() {
            AgentStateMachine.checkLegal(AgentRunStatus.EXECUTING, AgentRunStatus.TOOL_FAILED);
        }

        @Test
        @DisplayName("PLANNED → BUDGET_EXCEEDED 合法")
        void plannedToBudget() {
            AgentStateMachine.checkLegal(AgentRunStatus.PLANNED, AgentRunStatus.BUDGET_EXCEEDED);
        }

        @Test
        @DisplayName("EXECUTING → TIMED_OUT 合法")
        void executingToTimeout() {
            AgentStateMachine.checkLegal(AgentRunStatus.EXECUTING, AgentRunStatus.TIMED_OUT);
        }

        @Test
        @DisplayName("EXECUTING → CANCELLED 合法")
        void executingToCancelled() {
            AgentStateMachine.checkLegal(AgentRunStatus.EXECUTING, AgentRunStatus.CANCELLED);
        }

        @Test
        @DisplayName("EXECUTING → REFUSED_PERMISSION 合法")
        void executingToPermissionDenied() {
            AgentStateMachine.checkLegal(
                    AgentRunStatus.EXECUTING, AgentRunStatus.REFUSED_PERMISSION);
        }
    }

    @Nested
    @DisplayName("非法转换")
    class Illegal {

        @Test
        @DisplayName("RECEIVED → EXECUTING 非法 (跳过 ROUTED/PLANNED)")
        void skipPhase() {
            assertThatThrownBy(
                            () ->
                                    AgentStateMachine.checkLegal(
                                            AgentRunStatus.RECEIVED, AgentRunStatus.EXECUTING))
                    .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
        }

        @Test
        @DisplayName("EXECUTING → ROUTED 非法 (反向不可逆)")
        void reverse() {
            assertThatThrownBy(
                            () ->
                                    AgentStateMachine.checkLegal(
                                            AgentRunStatus.EXECUTING, AgentRunStatus.ROUTED))
                    .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
        }
    }

    @Nested
    @DisplayName("终态保护")
    class Terminal {

        @Test
        @DisplayName("ANSWERED 不能再转到任何状态")
        void answeredImmutable() {
            for (AgentRunStatus target : AgentRunStatus.values()) {
                if (target == AgentRunStatus.ANSWERED) continue;
                assertThatThrownBy(
                                () -> AgentStateMachine.checkLegal(AgentRunStatus.ANSWERED, target))
                        .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
            }
        }

        @Test
        @DisplayName("CANCELLED 终态不能再转 ANSWERED (终态竞争保护)")
        void cancelledNoAnswer() {
            assertThatThrownBy(
                            () ->
                                    AgentStateMachine.checkLegal(
                                            AgentRunStatus.CANCELLED, AgentRunStatus.ANSWERED))
                    .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
        }

        @Test
        @DisplayName("TIMED_OUT 终态不能再转 ANSWERED")
        void timedOutNoAnswer() {
            assertThatThrownBy(
                            () ->
                                    AgentStateMachine.checkLegal(
                                            AgentRunStatus.TIMED_OUT, AgentRunStatus.ANSWERED))
                    .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
        }

        @Test
        @DisplayName("REFUSED_PERMISSION 终态不能再转 EXECUTING")
        void permissionNoExecute() {
            assertThatThrownBy(
                            () ->
                                    AgentStateMachine.checkLegal(
                                            AgentRunStatus.REFUSED_PERMISSION,
                                            AgentRunStatus.EXECUTING))
                    .isInstanceOf(AgentStateMachine.IllegalTransitionException.class);
        }
    }
}
