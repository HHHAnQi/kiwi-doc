package com.xxx.ragdoc.application.chat.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.pipeline.ChatExecutionContext;
import com.xxx.ragdoc.application.chat.pipeline.ComparisonWorkflowPipeline;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PR-6c.2: 验证 {@link ComparisonWorkflowPipeline} 在 Feature Flag 不同取值时的接线。
 *
 * <p>关键不变量 (Revision §11.1 §11.2 §3):
 *
 * <ul>
 *   <li>Flag=false → 100% 进 ChatService.chat 旧路径
 *   <li>Flag=true + Agent Path 失败 (业务终态 / Composer 业务异常) → 不回退旧路径 (返回 NO_RECALL)
 *   <li>Flag=true + Agent Path 配置/初始化异常 + compatibility-fallback=false → 仍 NO_RECALL (不回退)
 *   <li>Flag=true + Agent Path 配置/初始化异常 + compatibility-fallback=true → 回退旧路径
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ComparisonWorkflowPipeline - PR-6c.2 Flag 接线")
class ComparisonWorkflowPipelineTest {

    @Mock private ChatService chatService;
    @Mock private ComparisonAgentExecutor agentExecutor;
    private ComparisonExecutorProperties properties;
    private ComparisonWorkflowPipeline pipeline;

    @BeforeEach
    void setup() {
        properties = new ComparisonExecutorProperties();
        pipeline = new ComparisonWorkflowPipeline(chatService, agentExecutor, properties);
    }

    private ChatExecutionContext context() {
        RouterDecision d =
                new RouterDecision(
                        TaskIntent.COMPARISON,
                        ExecutionStrategy.FIXED_WORKFLOW,
                        List.of("v1", "v2"),
                        Map.of(),
                        1.0,
                        "TWO_VERSION_COMPARE");
        return new ChatExecutionContext(
                "req-1",
                new Principal("tA", "u1", java.util.Set.of(), null),
                ChatMode.AUTO,
                PipelineType.FIXED_WORKFLOW,
                new TraceId("t-1"),
                null,
                d);
    }

    private ChatCommand command() {
        return new ChatCommand("对比 v1 与 v2", null, null, null, null, null, "conv-1");
    }

    @Test
    @DisplayName("Flag=false → 调用 ChatService.chat 两次 (PR-3 旧路径, 无回归)")
    void flagFalseLegacyPath() throws Exception {
        properties.setComparisonExecutorEnabled(false);
        // PR-3 旧路径返回非空 citations, 让 mergeAndAssemble 不被单终态 NO_RECALL 拒
        List<ChatResult.Citation> cits =
                List.of(
                        new ChatResult.Citation(10L, 1L, 0, "snippet", "ctx", List.of()),
                        new ChatResult.Citation(20L, 2L, 0, "snippet", "ctx", List.of()));
        ChatResult lite = new ChatResult("A:", cits, StateHint.OK, new TraceId("t-1"), null, null);
        when(chatService.chat(any(), any(), anyString())).thenReturn(lite);

        ChatResult r = pipeline.execute(command(), context());

        verify(chatService, times(2)).chat(any(), any(), anyString());
        verify(agentExecutor, times(0)).execute(any(), any(), any(), anyString(), any(), any());
        assertThat(r.stateHint()).isEqualTo(StateHint.OK);
    }

    @Test
    @DisplayName("Flag=true → 调用 AgentExecutor, 不调 ChatService.chat 一线 A/B")
    void flagTrueAgentPath() throws Exception {
        properties.setComparisonExecutorEnabled(true);
        ChatResult okResult =
                new ChatResult(
                        "agent answer", List.of(), StateHint.OK, new TraceId("t-1"), null, null);
        when(agentExecutor.execute(any(), any(), any(), anyString(), any(), any()))
                .thenReturn(okResult);

        ChatResult r = pipeline.execute(command(), context());

        verify(chatService, times(0)).chat(any(), any(), anyString());
        verify(agentExecutor, times(1)).execute(any(), any(), any(), anyString(), any(), any());
        assertThat(r.answer()).isEqualTo("agent answer");
    }

    @Test
    @DisplayName("Flag=true + AgentExecutor 抛业务异常 → 不回退, 返回结构化 NO_RECALL")
    void flagTrueBusinessFailureNoFallback() throws Exception {
        properties.setComparisonExecutorEnabled(true);
        properties.setCompatibilityFallbackEnabled(true); // 即使显式 fallback 也不应回退业务失败
        when(agentExecutor.execute(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("BUDGET_EXCEEDED"));

        ChatResult r = pipeline.execute(command(), context());

        verify(chatService, times(0)).chat(any(), any(), anyString());
        assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
    }

    @Test
    @DisplayName("Flag=true + 初始化异常 (AgentRunInitializationException) + fallback=true → 回退旧路径")
    void flagTrueInitFailureFallback() throws Exception {
        properties.setComparisonExecutorEnabled(true);
        properties.setCompatibilityFallbackEnabled(true);
        when(agentExecutor.execute(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(
                        new com.xxx.ragdoc.application.chat.agent.AgentRunInitializationException(
                                "r1", "init failed"));
        List<ChatResult.Citation> cits =
                List.of(new ChatResult.Citation(10L, 1L, 0, "s", "c", List.of()));
        ChatResult lite = new ChatResult("L:", cits, StateHint.OK, new TraceId("t-1"), null, null);
        when(chatService.chat(any(), any(), anyString())).thenReturn(lite);

        ChatResult r = pipeline.execute(command(), context());

        verify(chatService, org.mockito.Mockito.atLeastOnce()).chat(any(), any(), anyString());
        assertThat(r.stateHint()).isEqualTo(StateHint.OK);
    }

    @Test
    @DisplayName("Flag=true + 初始化异常 + fallback=false → 不回退, 返回 EMPTY_KB")
    void flagTrueInitFailureNoFallback() throws Exception {
        properties.setComparisonExecutorEnabled(true);
        properties.setCompatibilityFallbackEnabled(false);
        when(agentExecutor.execute(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(
                        new com.xxx.ragdoc.application.chat.agent.AgentRunInitializationException(
                                "r1", "init failed"));

        ChatResult r = pipeline.execute(command(), context());

        verify(chatService, times(0)).chat(any(), any(), anyString());
        assertThat(r.stateHint()).isEqualTo(StateHint.EMPTY_KB);
    }
}
