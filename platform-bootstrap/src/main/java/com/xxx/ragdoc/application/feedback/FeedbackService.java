package com.xxx.ragdoc.application.feedback;

import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.feedback.command.FeedbackCommand;
import com.xxx.ragdoc.application.feedback.command.SubmitFeedbackResult;
import com.xxx.ragdoc.application.feedback.port.FeedbackRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
import com.xxx.ragdoc.domain.shared.TraceId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交反馈用例。
 *
 * <p>核心安全 / 闭环纪律:
 *
 * <ol>
 *   <li>traceId 正则白名单(防日志/SQL 注入)
 *   <li>查 chat_traces 表校验软引用合法(查不到抛 TRACE_NOT_FOUND, ADR-0003)
 *   <li>预检 feedbacks.traceId UNIQUE(已存在抛 FEEDBACK_EXISTS)
 *   <li>HTML 转义 correctedAnswer / comment(防 XSS)
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final ChatTracesRepository chatTracesRepository;
    private final FeedbackRepository feedbackRepository;

    @Transactional
    public SubmitFeedbackResult submit(FeedbackCommand cmd) {
        TraceId tid = new TraceId(cmd.traceId());

        // 1. 软引用合法性校验(ADR-0003)
        if (!chatTracesRepository.existsByTraceId(tid.value())) {
            log.warn("feedback.trace_not_found trace_id={}", tid.value());
            throw new DomainException(ErrorCode.TRACE_NOT_FOUND, "trace_id 不存在: " + tid.value());
        }

        // 2. UNIQUE 预检(DB 兜底 + 应用层友好错误)
        if (feedbackRepository.existsByTraceId(tid.value())) {
            log.info("feedback.duplicate trace_id={}", tid.value());
            throw new DomainException(ErrorCode.FEEDBACK_EXISTS, "trace_id 已有反馈, 不允许重复提交");
        }

        // 3. 创建聚合根
        Feedback feedback =
                Feedback.newFeedback(
                        tid, cmd.rating(), cmd.correctedAnswer(), cmd.comment(), cmd.userId());

        // 4. 持久化(Mapper 在 infra 层做 HTML 转义)
        feedback = feedbackRepository.save(feedback, cmd.correctedAnswer(), cmd.comment());

        log.info(
                "feedback.created trace_id={}, feedback_id={}, rating={}",
                tid.value(),
                feedback.id(),
                feedback.rating());

        return new SubmitFeedbackResult(feedback.id());
    }

    /** 管理员列表(仅)。 权限校验在 Controller 层守门(admin token)。 */
    @Transactional(readOnly = true)
    public Page<Feedback> list(Rating rating, Pageable pageable) {
        return feedbackRepository.list(rating, pageable);
    }
}
