package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.chat.verification.VerificationResult;
import com.xxx.ragdoc.application.chat.verification.VerificationResult.CitationScore;
import com.xxx.ragdoc.application.chat.verification.VerificationResult.Outcome;
import com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort;
import com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort.Evidence;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Task 7: ChatService 与 CitationVerifierPort 集成测试。
 *
 * <p>任务要求: "错误引用案例" — verify FAIL 时按 OnFail 配置触发不同行为。
 *
 * <ul>
 *   <li>REFUSE → hint=VERIFY_FAILED, answer=拒答模板
 *   <li>WARN_ONLY → hint 仍 OK, 但 citations 写 verifyScore
 *   <li>score 高 → OK 透传
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Task 7 ChatService × CitationVerifier")
class ChatServiceCitationVerifierTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private ChatMessages chatMessages;
    @Mock private RetrieveService retrieveService;
    @Mock private ChatClient chatClient;
    @Mock private TraceObserver traceObserver;
    @Mock private MetricsPort metrics;
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.ConversationStore conversationStore;
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort queryContextualizer;
    @Mock private com.xxx.ragdoc.application.chat.conversation.port.PromptAssemblerPort promptAssembler;
    @Mock private CitationVerifierPort citationVerifier;
    @Mock private CitationVerifierProperties citationVerifierProperties;

    @InjectMocks private ChatService chatService;

    private static final TraceId TID = new TraceId("trace1234");

    @BeforeEach
    void setup() throws Exception {
        when(chatMessages.getEmptyKbMessage()).thenReturn("EMPTY_MSG");
        when(chatMessages.getNoRecallMessage()).thenReturn("NORECALL_MSG");
        when(chatMessages.getLlmDegradedMessage()).thenReturn("LLM_FAIL:");
        when(chatMessages.verifierRefusal(anyDouble())).thenReturn("VERIFY_REFUSED");

        // KB 非空 + 有召回 + 非 EMPTY_KB 路径
        when(documentRepository.countByStatus(any())).thenReturn(1L);
        when(retrieveService.retrieve(any()))
                .thenReturn(
                        new RetrieveService.RetrieveResult(
                                List.of(
                                        new RetrieveService.Citation(
                                                19L, 6L, 0, "Sentinel 文本", "Sentinel 限流策略全文",
                                                0.9f, List.of("sec"))),
                                "not_enabled",
                                0.9f,
                                0f));
        when(chatClient.chat(anyString(), anyList())).thenReturn("Sentinel 是流控组件");
        lenient().when(traceObserver.startTrace(any(), any(), any())).thenReturn(TID.value());

        // verifier bean + properties injected
        chatService.setCitationVerifier(citationVerifier);
        ReflectionTestUtils.setField(chatService, "citationVerifierProperties", citationVerifierProperties);
        when(citationVerifierProperties.isEnabled()).thenReturn(true);
        when(citationVerifierProperties.getScoreThreshold()).thenReturn(0.5);
    }

    private ChatResult runChat() {
        return chatService.chat(new ChatCommand("Sentinel 是什么?", null, null), TID, null);
    }

    @Nested
    @DisplayName("PASS: answer 被 citation 支持")
    class Pass {
        @Test
        @DisplayName("score > threshold → hint=OK, answer 原样, citations 带 verifyScore")
        void scoreAboveThresholdPasses() {
            when(citationVerifier.verify(anyString(), anyList()))
                    .thenReturn(
                            new VerificationResult(
                                    Outcome.PASS, 0.9,
                                    List.of(new CitationScore(19L, VerificationResult.Verdict.ENTAILMENT, 0.9)),
                                    null));

            ChatResult r = runChat();

            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.answer()).isEqualTo("Sentinel 是流控组件");
            assertThat(r.verification().outcome()).isEqualTo(Outcome.PASS);
            assertThat(r.citations().get(0).verifyScore()).isCloseTo(0.9, within(0.001));
        }
    }

    @Nested
    @DisplayName("FAIL + OnFail=REFUSE: 错误引用案例")
    class FailRefuse {
        @Test
        @DisplayName("contradiction + REFUSE → hint=VERIFY_FAILED + 拒答模板")
        void failRefuse() {
            when(citationVerifierProperties.getOnFail())
                    .thenReturn(CitationVerifierProperties.OnFail.REFUSE);
            when(citationVerifier.verify(anyString(), anyList()))
                    .thenReturn(
                            new VerificationResult(
                                    Outcome.FAIL, 0.0,
                                    List.of(new CitationScore(19L, VerificationResult.Verdict.CONTRADICTION, 0.0)),
                                    null));

            ChatResult r = runChat();

            assertThat(r.stateHint()).isEqualTo(StateHint.VERIFY_FAILED);
            assertThat(r.answer()).contains("VERIFY_REFUSED");
            assertThat(r.verification().outcome()).isEqualTo(Outcome.FAIL);
        }
    }

    @Nested
    @DisplayName("FAIL + OnFail=WARN_ONLY")
    class FailWarnOnly {
        @Test
        @DisplayName("contradiction + WARN_ONLY → hint 仍 OK, 但 citations 含低 score")
        void failWarnOnly() {
            when(citationVerifierProperties.getOnFail())
                    .thenReturn(CitationVerifierProperties.OnFail.WARN_ONLY);
            // ChatService 在 WARN_ONLY 路径会把 errorMessage 改为 "WARN_ONLY:..." 前缀 —
            // mock verifier 直接返, ChatService 内部 transform. setup mock 返原始 fail:
            when(citationVerifier.verify(anyString(), anyList()))
                    .thenReturn(
                            new VerificationResult(
                                    Outcome.FAIL, 0.2,
                                    List.of(new CitationScore(19L, VerificationResult.Verdict.UNKNOWN, 0.2)),
                                    null));

            ChatResult r = runChat();

            // WARN_ONLY: hint 仍 OK
            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.answer()).isEqualTo("Sentinel 是流控组件");
            // citations 含 score (即使低)
            assertThat(r.citations().get(0).verifyScore()).isCloseTo(0.2, within(0.001));
            assertThat(r.verification().outcome()).isEqualTo(Outcome.FAIL);
        }
    }

    @Nested
    @DisplayName("验证异常 (LLM fail / ERROR outcome) 不改 hint")
    class ErrorOutcome {
        @Test
        @DisplayName("verifier 返 ERROR → hint=OK, main answer 透传 (不挂)")
        void errorDoesNotFailChat() {
            when(citationVerifierProperties.getOnFail())
                    .thenReturn(CitationVerifierProperties.OnFail.REFUSE);
            when(citationVerifier.verify(anyString(), anyList()))
                    .thenReturn(VerificationResult.error("LLM judge timeout"));

            ChatResult r = runChat();

            // ERROR 不等于 FAIL, 主 chat 流程正常透传
            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.answer()).isEqualTo("Sentinel 是流控组件");
            assertThat(r.verification().outcome()).isEqualTo(Outcome.ERROR);
        }
    }

    @Nested
    @DisplayName("SKIPPED (citations 空): 不调 verifier")
    class Skipped {
        @Test
        @DisplayName("yq 无 citations → verification=null 透传")
        void noCitationsSkipsVerify() {
            // 改 mock 让召回返空 → NO_RECALL (不达 OK 分支), verification 应 null
            when(retrieveService.retrieve(any())).thenReturn(RetrieveService.RetrieveResult.empty());

            ChatResult r = runChat();

            assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
            assertThat(r.verification()).isNull();
            verifyNoInteractions(citationVerifier);
        }
    }

    @Nested
    @DisplayName("verifier disabled: 不调")
    class Disabled {
        @Test
        @DisplayName("properties.enabled=false → verification=null")
        void disabledSkipsVerify() {
            when(citationVerifierProperties.isEnabled()).thenReturn(false);

            ChatResult r = runChat();

            assertThat(r.stateHint()).isEqualTo(StateHint.OK);
            assertThat(r.verification()).isNull();
            verifyNoInteractions(citationVerifier);
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double tol) {
        return org.assertj.core.data.Offset.offset(tol);
    }
}
