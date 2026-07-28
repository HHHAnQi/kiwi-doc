package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.auth.AuthProperties;
import com.xxx.ragdoc.application.feedback.FeedbackService;
import com.xxx.ragdoc.application.feedback.command.FeedbackCommand;
import com.xxx.ragdoc.application.feedback.command.SubmitFeedbackResult;
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
 * Feedback REST 接口(V1)。
 *
 * <p>鉴权纪律:
 *
 * <ul>
 *   <li>POST /feedback: 任何登录用户(dev token 即可)
 *   <li>GET /feedbacks: 仅管理员(admin token)
 * </ul>
 *
 * <p>V4 升级到完整 RBAC + JWT。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "问答反馈")
public class FeedbackController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FeedbackService feedbackService;
    private final AuthProperties authProperties;

    @PostMapping("/feedback")
    @Operation(summary = "提交反馈", description = "trace_id 必须来自真实 chat 调用, 由 chat_traces 校验")
    public ResponseEntity<FeedbackCreatedResponse> submit(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody FeedbackRequest req) {
        requireDevOrAdmin(auth);

        FeedbackCommand cmd =
                new FeedbackCommand(
                        req.traceId(),
                        Rating.parse(req.rating()),
                        req.correctedAnswer(),
                        req.comment(),
                        "default");
        SubmitFeedbackResult result = feedbackService.submit(cmd);

        return ResponseEntity.status(201).body(new FeedbackCreatedResponse(result.feedbackId()));
    }

    @GetMapping("/feedbacks")
    @Operation(summary = "反馈列表(管理员)", description = "V1 仅 admin token 可访问")
    public FeedbackListResponse list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String rating,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdmin(auth);

        // 分页参数兜底
        int safePage = Math.max(1, page) - 1; // Spring Pageable 从 0 起
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Rating ratingFilter = rating == null || rating.isBlank() ? null : Rating.parse(rating);
        Page<Feedback> result =
                feedbackService.list(ratingFilter, PageRequest.of(safePage, safeSize));

        List<FeedbackItem> items = result.getContent().stream().map(FeedbackItem::from).toList();
        return FeedbackListResponse.of(items, result.getTotalElements(), page, safeSize);
    }

    // ============================================================
    // 鉴权(V1 极简, V4 升级 RBAC)
    // ============================================================

    private void requireDevOrAdmin(String auth) {
        String token = extractToken(auth);
        if (token == null
                || (!token.equals(authProperties.getDevToken())
                        && !token.equals(authProperties.getAdminToken()))) {
            throw AuthErrors.unauthorized("无效或缺失的 token");
        }
    }

    private void requireAdmin(String auth) {
        String token = extractToken(auth);
        if (token == null || !token.equals(authProperties.getAdminToken())) {
            throw AuthErrors.forbidden("仅管理员可访问");
        }
    }

    private static String extractToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }
}
