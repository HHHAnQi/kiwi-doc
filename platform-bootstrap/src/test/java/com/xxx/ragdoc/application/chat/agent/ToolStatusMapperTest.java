package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.tool.ToolStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-6b.3: {@link ToolStatusMapper} 映射表单测 (9 ToolStatus 全覆盖 + null fail-closed)。
 */
@DisplayName("ToolStatusMapper - PR-6b.3 ToolStatus → AgentStepStatus 表驱动")
class ToolStatusMapperTest {

    @Test
    @DisplayName("SUCCESS + 有 Evidence → SUCCEEDED")
    void successWithEvidence() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.SUCCESS, true))
                .isEqualTo(AgentStepStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("SUCCESS + 无 Evidence → EMPTY (Revision §6 关键)")
    void successNoEvidenceEmpty() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.SUCCESS, false))
                .isEqualTo(AgentStepStatus.EMPTY);
    }

    @Test
    @DisplayName("EMPTY_RESULT → EMPTY")
    void emptyResultMapsEmpty() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.EMPTY_RESULT, false))
                .isEqualTo(AgentStepStatus.EMPTY);
    }

    @Test
    @DisplayName("INVALID_ARGUMENT → FAILED_TERMINAL (不可重试)")
    void invalidArgumentTerminal() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.INVALID_ARGUMENT, false))
                .isEqualTo(AgentStepStatus.FAILED_TERMINAL);
    }

    @Test
    @DisplayName("PERMISSION_DENIED → PERMISSION_DENIED")
    void permissionDenied() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.PERMISSION_DENIED, false))
                .isEqualTo(AgentStepStatus.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("TIMEOUT → TIMED_OUT")
    void timeout() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.TIMEOUT, false))
                .isEqualTo(AgentStepStatus.TIMED_OUT);
    }

    @Test
    @DisplayName("DEPENDENCY_UNAVAILABLE + RETRYABLE_ERROR → FAILED_TERMINAL (PR-6b 不自动重试, 直接收敛)")
    void dependencyAndRetryableBothTerminal() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.DEPENDENCY_UNAVAILABLE, false))
                .isEqualTo(AgentStepStatus.FAILED_TERMINAL);
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.RETRYABLE_ERROR, false))
                .isEqualTo(AgentStepStatus.FAILED_TERMINAL);
    }

    @Test
    @DisplayName("TERMINAL_ERROR → FAILED_TERMINAL")
    void terminalError() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.TERMINAL_ERROR, false))
                .isEqualTo(AgentStepStatus.FAILED_TERMINAL);
    }

    @Test
    @DisplayName("CANCELLED → CANCELLED")
    void cancelled() {
        assertThat(ToolStatusMapper.toStepStatus(ToolStatus.CANCELLED, false))
                .isEqualTo(AgentStepStatus.CANCELLED);
    }

    @Test
    @DisplayName("null → FAILED_TERMINAL (fail-closed)")
    void nullFailClosed() {
        assertThat(ToolStatusMapper.toStepStatus(null, false))
                .isEqualTo(AgentStepStatus.FAILED_TERMINAL);
        assertThat(ToolStatusMapper.errorCode(null)).isEqualTo("UNKNOWN_TOOL_STATUS");
    }
}
