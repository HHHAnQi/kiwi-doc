package com.xxx.ragdoc.interfaces.rest;

import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.document.port.Retriever;
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
 * 直接召回 REST 接口(检索评测专用)。
 *
 * <p>仅在 {@link RetrieveService} 上包一层薄转换: 接 {@code /api/v1/retrieve}, 调 {@link
 * RetrieveService#retrieve}, 返 {@link RetrieveResponse}。不接入 ChatService / LLM,
 * 让离线评测直接拿到含 score 的原始召回结果算 MRR/NDCG, 不耗 LLM token。
 *
 * <p>零业务侵入: 不修改 RetrieveService / ChatService / ChatController 任何一行;
 * `/api/v1/chat` 行为完全不变。鉴权沿用 app.auth.dev-token(由全局 filter/拦截器处理, 与 ChatController 一致)。
 *
 * <p>模型版本注入: 注意 ArchUnit 规则 "interfaces 不直接访问 infrastructure" (见 ArchitectureTest#interfaces不直接访问Infrastructure),
 * 故这里**不**注入 {@code LlmProperties/EmbeddingProperties}(它们在 infrastructure 包下), 改用
 * {@code @Value} 把同一属性键绑成纯 String — 不引入类依赖, 不破规则。{@link RerankProperties} 已在 application 包,
 * 可直接注入。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/retrieve")
@RequiredArgsConstructor
@Tag(name = "Retrieve", description = "直接召回(检索评测专用, 不接入 LLM)")
public class RetrieveController {

    private final RetrieveService retrieveService;
    private final RerankProperties rerankProperties;

    // @Value 注入纯 String, 避免 interfaces→infrastructure 的 ArchUnit 违规
    // (LlmProperties/EmbeddingProperties 都在 infrastructure 包下)
    @Value("${llm.model:}")
    private String llmModel;

    @Value("${embedding.model:}")
    private String embeddingModel;

    @PostMapping
    @Operation(
            summary = "直接召回(评测用)",
            description =
                    "对给定 query 执行 hybrid(dense+BM25) 召回 + 可选 reranker 精排, 返回含 score 的"
                            + " citation 列表。不调用 LLM。主要给 eval/ 离线检索评测用, 也可用于线上 retrieval 调试。")
    public RetrieveResponse retrieve(@Valid @RequestBody RetrieveRequest request) {
        // 为复用 ChatCommand(同一份 query/topK/source/version/language 校验), 借用其 canonical 构造器。
        // conversation_id 不适用(直召回无多轮语义)。
        ChatCommand cmd =
                new ChatCommand(
                        request.query(),
                        request.docId(),
                        request.topK(),
                        request.source(),
                        request.version(),
                        request.language());
        // Task 5: per-request mode override (null=全局默认, 兼容老调用方)
        Retriever.Mode mode = parseMode(request.mode());
        RetrieveService.RetrieveResult result = retrieveService.retrieve(cmd, mode, request.enhance());
        log.info(
                "retrieve.endpoint_done trace_id={}, mode={}, rerank_state={}, items={}",
                org.slf4j.MDC.get(TraceIdFilter.MDC_TRACE_KEY),
                mode,
                result.rerankState(),
                result.items().size());
        return RetrieveResponse.from(
                result,
                llmModel == null ? "" : llmModel,
                embeddingModel == null ? "" : embeddingModel,
                rerankProperties);
    }

    /** Task 5: 字符串 mode → Retriever.Mode; 非法值返 null 走全局默认而非报错 (老路径容忍)。 */
    static Retriever.Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Retriever.Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
