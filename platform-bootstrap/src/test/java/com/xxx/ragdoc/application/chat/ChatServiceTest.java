package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** ChatService V1 stub 行为单元测试。 覆盖 EMPTY_KB / NO_RECALL / DOC_NOT_READY / DOC_NOT_FOUND 四个关键路径。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private ChatMessages chatMessages;

    @InjectMocks private ChatService chatService;

    private static final TraceId TID = new TraceId("a1b2c3d4");

    @BeforeEach
    void setupMessages() {
        when(chatMessages.getEmptyKbMessage()).thenReturn("EMPTY_MSG");
        when(chatMessages.getNoRecallMessage()).thenReturn("NORECALL_MSG");
    }

    @Nested
    @DisplayName("EMPTY_KB: 0 个 READY 文档")
    class EmptyKb {
        @Test
        @DisplayName("应返回 state_hint=EMPTY_KB + 友好文案 + 空 citations")
        void shouldReturnEmptyKb() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

            ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.EMPTY_KB);
            assertThat(r.answer()).isEqualTo("EMPTY_MSG");
            assertThat(r.citations()).isEmpty();
        }

        @Test
        @DisplayName("必须落 chat_traces 记录(state_hint=EMPTY_KB)")
        void shouldWriteChatTrace() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

            chatService.chat(new ChatCommand("hello", null, 5), TID);

            ArgumentCaptor<ChatTrace> captor = ArgumentCaptor.forClass(ChatTrace.class);
            verify(chatTracesRepository).save(captor.capture());
            ChatTrace saved = captor.getValue();
            assertThat(saved.traceId()).isEqualTo(TID);
            assertThat(saved.stateHint()).isEqualTo(StateHint.EMPTY_KB);
            assertThat(saved.queryHash()).matches("^[a-fA-F0-9]{64}$");
            assertThat(saved.queryLen()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("NO_RECALL: ≥1 READY 文档但 stub 无 chunks")
    class NoRecall {
        @Test
        @DisplayName("应返回 state_hint=NO_RECALL")
        void shouldReturnNoRecall() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(3L);

            ChatResult r = chatService.chat(new ChatCommand("Sentinel 限流", null, 5), TID);

            assertThat(r.stateHint()).isEqualTo(StateHint.NO_RECALL);
            assertThat(r.answer()).isEqualTo("NORECALL_MSG");
            assertThat(r.citations()).isEmpty();
        }

        @Test
        @DisplayName("V1 stub 永不调召回, 直接走 NO_RECALL")
        void shouldNotHitRealRetrieval() {
            when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(1L);

            chatService.chat(new ChatCommand("q", null, null), TID);

            // chatTracesRepository 仅被调一次(写记录), 没有任何召回相关调用
            verify(chatTracesRepository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("限制 doc_id 校验")
    class DocIdValidation {
        @Test
        @DisplayName("doc_id 不存在 → 404 DOC_NOT_FOUND")
        void shouldRejectNonExistentDoc() {
            when(documentRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.chat(new ChatCommand("q", 999L, 5), TID))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((NotFoundException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_FOUND));
        }

        @Test
        @DisplayName("doc 状态=PARSING → 409 DOC_NOT_READY")
        void shouldRejectNotReadyDoc() {
            Document doc =
                    Document.restore(
                            new DocumentId(1L),
                            new ContentHash(
                                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                            "f.pdf",
                            "application/pdf",
                            1L,
                            "default",
                            DocumentStatus.PARSING,
                            0,
                            null,
                            java.util.List.of(),
                            false);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            assertThatThrownBy(() -> chatService.chat(new ChatCommand("q", 1L, 5), TID))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.DOC_NOT_READY));
        }

        @Test
        @DisplayName("非 READY 文档时, 不应写 chat_traces")
        void shouldNotWriteTraceOnRejection() {
            Document doc =
                    Document.restore(
                            new DocumentId(1L),
                            new ContentHash(
                                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                            "f.pdf",
                            "application/pdf",
                            1L,
                            "default",
                            DocumentStatus.PARSING,
                            0,
                            null,
                            java.util.List.of(),
                            false);
            when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

            try {
                chatService.chat(new ChatCommand("q", 1L, 5), TID);
            } catch (Exception ignored) {
                // 期望抛异常
            }
            verify(chatTracesRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("响应含 trace_id(供前端反馈关联)")
    void responseShouldCarryTraceId() {
        when(documentRepository.countByStatus(DocumentStatus.READY)).thenReturn(0L);

        ChatResult r = chatService.chat(new ChatCommand("hello", null, 5), TID);

        assertThat(r.traceId()).isEqualTo(TID);
    }
}
