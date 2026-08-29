package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.feedback.FeedbackService;
import com.xxx.ragdoc.application.feedback.command.FeedbackCommand;
import com.xxx.ragdoc.application.feedback.command.SubmitFeedbackResult;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
import com.xxx.ragdoc.interfaces.rest.auth.AuthErrors;
import com.xxx.ragdoc.interfaces.rest.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Feedback REST 接口。
 *
 * <p>鉴权纪律 (P0 修复: 撤掉与 AuthFilter 并行的静态 token 双轨制):
 *
 * <ul>
 *   <li>POST /feedback: 任何通过 AuthFilter 的登录用户, 提交者记为 principal.userId() (此前硬编码 "default", 反馈归属失真)
 *   <li>GET /feedbacks: 仅 role:admin
 * </ul>
 *
 * <p>此前本类自带一套 APP_DEV_TOKEN/APP_ADMIN_TOKEN 静态比对, 与 AuthFilter 的 DB principal 体系互斥 — 正常 DB 用户 token
 * 会被 401 挡掉, 同时形成第二条凭据通道。现统一走 AuthContext。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "问答反馈")
public class FeedbackController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FeedbackService feedbackService;

    @PostMapping("/feedback")
    @Operation(summary = "提交反馈", description = "trace_id 必须来自真实 chat 调用, 由 chat_traces 校验")
    public ResponseEntity<FeedbackCreatedResponse> submit(@Valid @RequestBody FeedbackRequest req) {
        Principal p = AuthContext.currentPrincipal();
        FeedbackCommand cmd =
                new FeedbackCommand(
                        req.traceId(),
                        Rating.parse(req.rating()),
                        req.correctedAnswer(),
                        req.comment(),
                        p.userId());
        SubmitFeedbackResult result = feedbackService.submit(cmd);

        return ResponseEntity.status(201).body(new FeedbackCreatedResponse(result.feedbackId()));
    }

    @GetMapping("/feedbacks")
    @Operation(summary = "反馈列表(管理员)")
    public FeedbackListResponse list(
            @RequestParam(required = false) String rating,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Principal p = AuthContext.currentPrincipal();
        if (!p.isAdmin()) {
            throw AuthErrors.forbidden("仅管理员可访问");
        }

        // 分页参数兜底
        int safePage = Math.max(1, page) - 1; // Spring Pageable 从 0 起
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Rating ratingFilter = rating == null || rating.isBlank() ? null : Rating.parse(rating);
        Page<Feedback> result =
                feedbackService.list(ratingFilter, PageRequest.of(safePage, safeSize));

        List<FeedbackItem> items = result.getContent().stream().map(FeedbackItem::from).toList();
        return FeedbackListResponse.of(items, result.getTotalElements(), page, safeSize);
    }
}
