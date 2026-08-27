package com.xxx.ragdoc.application.chat.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
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
 * PR-7a: {@link ModelPlannerProvider} — JSON 解析路径 + Provider 错误转换 (不直接验证 happy JSON 反序列化, 因
 * ToolInput polymorphic)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelPlannerProvider - PR-7a JSON-only + 错误分类")
class ModelPlannerProviderTest {

    @Mock private ChatClient chatClient;
    private PlannerProperties props;
    private ModelPlannerProvider provider;

    @BeforeEach
    void setup() {
        props = new PlannerProperties();
        provider = new ModelPlannerProvider(chatClient, new ObjectMapper(), props);
    }

    private PlannerRequest request() {
        return new PlannerRequest(
                "r-1",
                "q",
                TaskIntent.MULTI_HOP,
                List.of(),
                Map.of(),
                List.of(EvidenceRequirement.fact("R1", "d", true)),
                EvidenceCoverageSummary.empty(),
                List.of(),
                new AgentBudgetView(3, 3, 3, 3, 30000, 1),
                List.of(new PlannerToolDescriptor("semantic_search", "v1", "d", Map.of())),
                0);
    }

    @Test
    @DisplayName("空字符串输出 → INVALID_JSON")
    void emptyOutput() throws Exception {
        when(chatClient.chat(anyString(), anyList())).thenReturn("");
        assertThatThrownBy(() -> provider.plan(request()))
                .isInstanceOf(PlannerException.class)
                .hasMessageContaining("empty output");
    }

    @Test
    @DisplayName("非 JSON 输出 → INVALID_JSON")
    void nonJsonOutput() throws Exception {
        when(chatClient.chat(anyString(), anyList())).thenReturn("I cannot help with that.");
        assertThatThrownBy(() -> provider.plan(request())).isInstanceOf(PlannerException.class);
    }

    @Test
    @DisplayName("ChatClient 抛 TimeoutException → 转 PlannerException TIMEOUT")
    void timeoutWrapped() throws Exception {
        when(chatClient.chat(anyString(), anyList()))
                .thenThrow(new java.util.concurrent.TimeoutException("timed out"));
        assertThatThrownBy(() -> provider.plan(request()))
                .isInstanceOf(PlannerException.class)
                .satisfies(
                        t ->
                                assertThat(((PlannerException) t).reason)
                                        .isEqualTo(PlannerException.Reason.TIMEOUT));
    }

    @Test
    @DisplayName("ChatClient 抛其他 RuntimeException → PROVIDER_ERROR")
    void otherException() throws Exception {
        when(chatClient.chat(anyString(), anyList()))
                .thenThrow(new RuntimeException("backend down"));
        assertThatThrownBy(() -> provider.plan(request()))
                .isInstanceOf(PlannerException.class)
                .satisfies(
                        t ->
                                assertThat(((PlannerException) t).reason)
                                        .isEqualTo(PlannerException.Reason.PROVIDER_ERROR));
    }

    @Test
    @DisplayName("extractJson: 含 ```json fenced + 普通 text → 正确提取")
    void extractJsonFenced() {
        String fenced = "Some preamble\n```json\n{\"a\":1}\n```\nTail";
        assertThat(ModelPlannerProvider.extractJson(fenced)).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("extractJson: 裸 JSON 块 → 正确提取")
    void extractJsonRaw() {
        String raw = "noise {\"planId\":\"x\",\"steps\":[]} trailing";
        assertThat(ModelPlannerProvider.extractJson(raw)).contains("planId");
    }

    @Test
    @DisplayName("buildPrompt: 包含 allowedTools + 安全警告 + 不含 token")
    void promptSafer() {
        String prompt = ModelPlannerProvider.buildPrompt(request(), props.getMaxPlanSteps());
        assertThat(prompt)
                .contains("allowedTools", "semantic_search")
                .contains("UNTRUSTED"); // 安全警告
        assertThat(prompt.toLowerCase())
                .doesNotContain("rawtoken=")
                .doesNotContain("tenantoverride="); // 不含身份 Literal
    }

    @Test
    @DisplayName("P1-B: LLM 真实调用点 → llm_calls{component=planner}(调用失败也计)")
    void plannerLlmCallMetric() throws Exception {
        com.xxx.ragdoc.application.metrics.MetricsPort m =
                org.mockito.Mockito.mock(com.xxx.ragdoc.application.metrics.MetricsPort.class);
        provider.setMetricsPort(m);
        when(chatClient.chat(anyString(), anyList())).thenThrow(new RuntimeException("llm down"));
        assertThatThrownBy(() -> provider.plan(request())).isInstanceOf(PlannerException.class);
        org.mockito.Mockito.verify(m).recordAgentLlmCall("planner");
    }
}
