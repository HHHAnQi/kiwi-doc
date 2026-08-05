package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.common.exception.BaseException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest;
import com.xxx.ragdoc.interfaces.rest.dto.RetrieveResponse;
import com.xxx.ragdoc.interfaces.rest.filter.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 5 / V11: AB 实验 REST 接口 — 强制 per-request mode override。
 *
 * <p>与 {@link RetrieveController} 区别:
 *
 * <ul>
 *   <li>路径 {@code /api/v1/retrieve/experiment}
 *   <li>{@code mode} 字段必填 (非 dense|hybrid 返 400); 普通路径 mode 可选
 *   <li>admin-token 守门: 仅 role:admin 可调 — 防滥用导致生产 Milvus 被刷
 * </ul>
 *
 * <p>用法: 离线评测 / 内部 ablation — agent 拿 admin-token 跑同一组 query 两次
 * (mode=dense / mode=hybrid), 收 metric 输出 dense_vs_hybrid report。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/retrieve")
@RequiredArgsConstructor
@Tag(name = "RetrieveExperiment", description = "Task 5 AB 实验: per-request mode override (admin-only)")
public class RetrieveExperimentController {

    private final RetrieveService retrieveService;
    private final RerankProperties rerankProperties;

    @Value("${llm.model:}")
    private String llmModel;

    @Value("${embedding.model:}")
    private String embeddingModel;

    @PostMapping("/experiment")
    @Operation(
            summary = "AB 实验检索 (admin-only)",
            description =
                    "与 /api/v1/retrieve 同语义, 但强制要求 mode ∈ {dense, hybrid}, 且仅 role:admin 可调."
                            + " 用于离线 / 灰度评测 dense vs hybrid recall.")
    public RetrieveResponse experiment(@Valid @RequestBody RetrieveRequest request) {
        // 守门: 仅 admin token
        if (!AuthContext.currentPrincipal().isAdmin()) {
            throw unauthorizedAB();
        }

        // 校验 mode 必填且合法 (不合法返 400 让评测脚本尽早发现配置错)
        Retriever.Mode mode = RetrieveController.parseMode(request.mode());
        if (mode == null) {
            throw new BaseException(
                    ErrorCode.SYS_INTERNAL,
                    "AB 实验 mode 必填且必须 ∈ {dense, hybrid}, 实际=" + request.mode()) {};
        }

        ChatCommand cmd =
                new ChatCommand(
                        request.query(),
                        request.docId(),
                        request.topK(),
                        request.source(),
                        request.version(),
                        request.language());
        RetrieveService.RetrieveResult result = retrieveService.retrieve(cmd, mode);
        log.info(
                "retrieve.experiment_done trace_id={}, mode={}, items={}",
                org.slf4j.MDC.get(TraceIdFilter.MDC_TRACE_KEY),
                mode,
                result.items().size());
        return RetrieveResponse.from(
                result,
                llmModel == null ? "" : llmModel,
                embeddingModel == null ? "" : embeddingModel,
                rerankProperties);
    }

    private static RuntimeException unauthorizedAB() {
        return new BaseException(ErrorCode.UNAUTHORIZED, "AB 实验接口仅 role:admin 可调") {};
    }
}
