package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Task 9 / V15: ChatService × TraceObserver 完整 trace 字段契约测试。
 *
 * <p>任务文档要求 11 字段全上 trace, badcase 一键追踪全链。本测试在 OK 路径下, 验证 4 个关键 observation 调用 (startTrace /
 * retrieve.observe / llm.observe / endTrace) 携带不 缺失的 metadata。
 *
 * <p>字段: request_id / user_id / conversation_id / query / retrieved_chunks / retrieval_score /
 * rerank_score / prompt_version / model_version / token_usage / latency。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Task 9 ChatService × TraceObserver 字段契约")
class ChatServiceTraceContractTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private ChatMessages chatMessages;
    @Mock private RetrieveService retrieveService;
    @Mock private ChatClient chatClient;
    @Mock private TraceObserver traceObserver;
    @Mock private MetricsPort metrics;

    @InjectMocks private ChatService chatService;

    private static final TraceId TID = new TraceId("trace9999");

    @BeforeEach
    void setup() throws Exception {
        // 用户登录模拟 (Task 9: user_id 必须从 AuthContext 出来)
        AuthContext.set(
                new com.xxx.ragdoc.domain.auth.Principal(
                        "default",
                        "userXYZ",
                        java.util.Set.of("role:default", "role:user"),
                        "token"));

        when(chatMessages.getEmptyKbMessage()).thenReturn("EMPTY");
        when(chatMessages.getNoRecallMessage()).thenReturn("NORECALL");
        when(chatMessages.getLlmDegradedMessage()).thenReturn("DEGRADED:");
        when(chatMessages.isPromptV2()).thenReturn(true);
        when(chatMessages.isPromptV2Citation()).thenReturn(true);

        // KB 非空, 召回有 hits, LLM 成功出答案 — 走 OK 路径
        when(documentRepository.countByStatus(any())).thenReturn(1L);
        when(retrieveService.retrieve(any()))
                .thenReturn(
                        new RetrieveService.RetrieveResult(
                                List.of(
                                        new RetrieveService.Citation(
                                                19L,
                                                6L,
                                                0,
                                                "Sentinel 文本短句",
                                                "Sentinel 限流策略全文较长",
                                                0.92f,
                                                List.of("sec"))),
                                "applied",
                                0.92f,
                                0.85f));
        when(chatClient.chat(anyString(), anyList())).thenReturn("Sentinel 是阿里流控组件");
        when(chatClient.currentModel()).thenReturn("qwen-max-2024");
        when(chatClient.lastUsage())
                .thenReturn(java.util.Optional.of(new ChatClient.TokenUsage(120, 35, 155)));
        lenient().when(traceObserver.startTrace(any(), any(), any())).thenReturn(TID.value());
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("OK 路径 emit 全 11 个 trace 必需字段")
    void okPathEmitsAllRequiredFields() {
        ChatResult r =
                chatService.chat(new ChatCommand("Sentinel 是什么?", null, null), TID, "conv-abc");
        assertThat(r.stateHint()).isEqualTo(StateHint.OK);

        // ============ startTrace: user_id + query + conversation_id + prompt_version + model
        // _version ============
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> startCaptor = ArgumentCaptor.forClass(Map.class);
        verify(traceObserver).startTrace(eq(TID.value()), eq("userXYZ"), startCaptor.capture());
        Map<String, Object> startMeta = startCaptor.getValue();
        assertThat(startMeta)
                .containsKeys(
                        "query", "conversation_id", "user_id", "prompt_version", "model_version");
        assertThat(startMeta.get("query")).isEqualTo("Sentinel 是什么?");
        assertThat(startMeta.get("user_id")).isEqualTo("userXYZ");
        assertThat(startMeta.get("conversation_id")).isEqualTo("conv-abc");
        assertThat(startMeta.get("prompt_version")).isEqualTo("v2-cite");
        assertThat(startMeta.get("model_version")).isEqualTo("qwen-max-2024");

        // ============ RETRIEVE observation: retrieved_chunks + retrieval_score + rerank_scor
        // e ============
        ArgumentCaptor<Map<String, Object>> retrieveMetaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(traceObserver)
                .observe(
                        eq(TID.value()),
                        eq(TraceObserver.ObservationType.RETRIEVE),
                        eq("retrieve"),
                        any(),
                        any(),
                        anyLong(),
                        retrieveMetaCaptor.capture());
        Map<String, Object> retrieveMeta = retrieveMetaCaptor.getValue();
        assertThat(retrieveMeta)
                .containsKeys("retrieved_chunks", "top1_retrieval_score", "top1_rerank_score");
        assertThat(retrieveMeta.get("top1_retrieval_score")).isNotNull();
        assertThat(retrieveMeta.get("top1_rerank_score")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks =
                (List<Map<String, Object>>) retrieveMeta.get("retrieved_chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).get("chunk_id")).isEqualTo(19L);
        assertThat(chunks.get(0).get("doc_id")).isEqualTo(6L);
        assertThat(chunks.get(0).get("score")).isEqualTo(0.92f);

        // ============ LLM observation: prompt_version + model_version + token_usage ============
        ArgumentCaptor<Map<String, Object>> llmMetaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(traceObserver)
                .observe(
                        eq(TID.value()),
                        eq(TraceObserver.ObservationType.LLM),
                        eq("llm.dashscope"),
                        any(),
                        any(),
                        anyLong(),
                        llmMetaCaptor.capture());
        Map<String, Object> llmMeta = llmMetaCaptor.getValue();
        assertThat(llmMeta)
                .containsKeys("prompt_version", "model_version", "token_usage", "latency_ms");
        assertThat(llmMeta.get("model_version")).isEqualTo("qwen-max-2024");
        assertThat(llmMeta.get("prompt_version")).isEqualTo("v2-cite");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) llmMeta.get("token_usage");
        assertThat(usage.get("prompt")).isEqualTo(120);
        assertThat(usage.get("completion")).isEqualTo(35);
        assertThat(usage.get("total")).isEqualTo(155);

        // ============ endTrace: chat_latency_ms ============
        ArgumentCaptor<Map<String, Object>> endMetaCaptor = ArgumentCaptor.forClass(Map.class);
        // 取最后一次 endTrace 调用 (sync chat 终点)
        verify(traceObserver, atLeastOnce()).endTrace(eq(TID.value()), endMetaCaptor.capture());
        Map<String, Object> endMeta = endMetaCaptor.getValue();
        assertThat(endMeta).containsKey("chat_latency_ms");
        assertThat(endMeta.get("chat_latency_ms")).isInstanceOf(Long.class);
        assertThat((long) endMeta.get("chat_latency_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("prompt_version 各 flag → 字符串映射: v2-cite / v2-plain / relaxed / baseline")
    void promptVersionStringMapping() {
        // 已 setup promptV2=true + V2Citation=true → v2-cite
        assertThat(invokeResolvePromptVersion()).isEqualTo("v2-cite");

        when(chatMessages.isPromptV2Citation()).thenReturn(false);
        assertThat(invokeResolvePromptVersion()).isEqualTo("v2-plain");

        when(chatMessages.isPromptV2()).thenReturn(false);
        when(chatMessages.isPromptRelaxRefusal()).thenReturn(true);
        assertThat(invokeResolvePromptVersion()).isEqualTo("relaxed");

        when(chatMessages.isPromptRelaxRefusal()).thenReturn(false);
        assertThat(invokeResolvePromptVersion()).isEqualTo("baseline");
    }

    private String invokeResolvePromptVersion() {
        // resolvePromptVersion 是 private 方法; 反射调避免破封装
        try {
            return (String) ReflectionTestUtils.invokeMethod(chatService, "resolvePromptVersion");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
