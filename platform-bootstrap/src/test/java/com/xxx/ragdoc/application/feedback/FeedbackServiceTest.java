package com.xxx.ragdoc.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.feedback.command.FeedbackCommand;
import com.xxx.ragdoc.application.feedback.command.SubmitFeedbackResult;
import com.xxx.ragdoc.application.feedback.port.FeedbackRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
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

/**
 * FeedbackService 单元测试。
 *
 * <p>覆盖四个安全/闭环关键场景:
 *
 * <ul>
 *   <li>TRACE_NOT_FOUND (软引用校验)
 *   <li>FEEDBACK_EXISTS (UNIQUE 预检)
 *   <li>XSS 转义 (持久化时由 infra 完成, service 不做)
 *   <li>正常提交
 * </ul>
 *
 * <p>HTML 转义落地由 JpaFeedbackRepository 完成, 此处仅断言 service 把原始文本传给 repo。 Lenient 严格度: 不同 happy-path
 * 用例的 save stub 入参可空可不空, 用 any() 宽松匹配避免误报。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    private static final String VALID_TRACE = "a1b2c3d4";
    private static final String INVALID_TRACE = "asd; DROP TABLE--";

    @Mock private ChatTracesRepository chatTracesRepository;
    @Mock private FeedbackRepository feedbackRepository;

    @InjectMocks private FeedbackService feedbackService;

    @Nested
    @DisplayName("Command 参数校验")
    class CommandValidation {
        @Test
        @DisplayName("非法 traceId 格式 → IllegalArgumentException")
        void illegalTraceFormat() {
            assertThatThrownBy(
                            () -> new FeedbackCommand(INVALID_TRACE, Rating.LIKE, null, null, "u"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("traceId 格式非法");
        }

        @Test
        @DisplayName("rating 不能为 null")
        void nullRating() {
            assertThatThrownBy(() -> new FeedbackCommand(VALID_TRACE, null, null, null, "u"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ID 太长(>64) → 非法")
        void tooLongTraceId() {
            String tooLong = "a".repeat(65);
            assertThatThrownBy(() -> new FeedbackCommand(tooLong, Rating.LIKE, null, null, "u"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("软引用校验 (ADR-0003)")
    class SoftRefCheck {
        @Test
        @DisplayName("trace_id 在 chat_traces 中查不到 → TRACE_NOT_FOUND")
        void shouldRejectUnknownTrace() {
            when(chatTracesRepository.existsByTraceId(VALID_TRACE)).thenReturn(false);

            FeedbackCommand cmd = new FeedbackCommand(VALID_TRACE, Rating.LIKE, null, null, "u");

            assertThatThrownBy(() -> feedbackService.submit(cmd))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.TRACE_NOT_FOUND));

            // 重要: 抛异常时不允许写 feedback
            verify(feedbackRepository, never()).save(any(), anyString(), anyString());
            verify(feedbackRepository, never()).existsByTraceId(anyString());
        }
    }

    @Nested
    @DisplayName("UNIQUE 预检")
    class UniqueCheck {
        @Test
        @DisplayName("trace_id 已有 feedback → FEEDBACK_EXISTS")
        void shouldRejectDup() {
            when(chatTracesRepository.existsByTraceId(VALID_TRACE)).thenReturn(true);
            when(feedbackRepository.existsByTraceId(VALID_TRACE)).thenReturn(true);

            FeedbackCommand cmd = new FeedbackCommand(VALID_TRACE, Rating.DISLIKE, "正确", "", "u");

            assertThatThrownBy(() -> feedbackService.submit(cmd))
                    .isInstanceOf(DomainException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((DomainException) ex).errorCode())
                                            .isEqualTo(ErrorCode.FEEDBACK_EXISTS));

            verify(feedbackRepository, never()).save(any(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("正常提交")
    class HappyPath {
        @Test
        @DisplayName("校验通过 → 持久化 + 返回 feedback_id")
        void shouldPersistAndReturnId() {
            when(chatTracesRepository.existsByTraceId(VALID_TRACE)).thenReturn(true);
            when(feedbackRepository.existsByTraceId(VALID_TRACE)).thenReturn(false);

            // 模拟 save 把 id 回填
            when(feedbackRepository.save(any(), anyString(), anyString()))
                    .thenAnswer(
                            inv -> {
                                Feedback f = inv.getArgument(0);
                                f.assignId(42L);
                                return f;
                            });

            FeedbackCommand cmd =
                    new FeedbackCommand(VALID_TRACE, Rating.DISLIKE, "正确答案", "页码错", "u");

            SubmitFeedbackResult result = feedbackService.submit(cmd);

            assertThat(result.feedbackId()).isEqualTo(42L);

            // 验证传给 repo 的 Feedback 含原始未转义文本(转义在 infra 层做)
            ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
            verify(feedbackRepository).save(captor.capture(), eq("正确答案"), eq("页码错"));
            Feedback saved = captor.getValue();
            assertThat(saved.correctedAnswer()).isEqualTo("正确答案"); // 原文, 未转义
            assertThat(saved.userId()).isEqualTo("u");
        }

        @Test
        @DisplayName("userId 为空时默认 default")
        void userIdDefaultsToDefault() {
            when(chatTracesRepository.existsByTraceId(VALID_TRACE)).thenReturn(true);
            when(feedbackRepository.existsByTraceId(VALID_TRACE)).thenReturn(false);
            when(feedbackRepository.save(any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                Feedback f = inv.getArgument(0);
                                f.assignId(1L);
                                return f;
                            });

            feedbackService.submit(new FeedbackCommand(VALID_TRACE, Rating.LIKE, null, null, null));

            ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
            verify(feedbackRepository).save(captor.capture(), any(), any());
            assertThat(captor.getValue().userId()).isEqualTo("default");
        }
    }

    private static String eq(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
