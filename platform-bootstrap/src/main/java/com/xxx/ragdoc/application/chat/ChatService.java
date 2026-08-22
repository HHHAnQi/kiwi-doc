package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.conversation.ContextualizeResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import com.xxx.ragdoc.application.chat.conversation.port.HistoryCompressorPort;
import com.xxx.ragdoc.application.chat.conversation.port.PromptAssemblerPort;
import com.xxx.ragdoc.application.chat.conversation.port.QueryContextualizerPort;
import com.xxx.ragdoc.application.chat.conversation.port.TopicShiftDetectorPort;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
// 架构债清理: 移除 infrastructure.conversation.* 直依赖, 改用 application 层端口 + ChatService 同包的
// ConversationProperties
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat 用例(V2-B 真实问答版本)。
 *
 * <p>决策优先级(不可破坏):
 *
 * <ul>
 *   <li>EMPTY_KB: 知识库 0 个 READY 文档(直接返回兜底文案, 不调 LLM 不召回)
 *   <li>NO_RECALL: 召回为空(无相关 chunk) — 返回兜底文案, 不调 LLM
 *   <li>LLM_DEGRADED: 召回了 chunk 但 LLM 调用失败 — 答案补 LLM 失败提示 + trace_id
 *   <li>OK: 召回成功 + LLM 返回答案 — 真实答案 + citations
 * </ul>
 *
 * <p>永不抛 chat 失败异常(EMPTY_KB/NO_RECALL/LLM_DEGRADED 全部走 200+state_hint)。 仅 4xx 客户端错误(docId 不存在 / doc
 * 状态非 READY) 走异常路径。
 *
 * <p>trace_id 贯穿: 每次调用写一条 chat_traces 记录(与响应同事务)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final DocumentRepository documentRepository;
    private final ChatTracesRepository chatTracesRepository;
    private final ChatMessages chatMessages;
    // V2-B 新增
    private final RetrieveService retrieveService;
    private final ChatClient chatClient;
    // V3-W3 Langfuse trace 接入(DoD-5); NoOpTraceObserver 兜底零开销
    private final TraceObserver traceObserver;

    // Phase 3.A: 5 SLO 计量。同步 chat 在 finish 里 record, chatStream 在 stream_done/failed/doFinally
    // record。
    // 架构债清理: 用 application 层端口 MetricsPort, 不直接持 infrastructure.RagdocMetrics。
    private final com.xxx.ragdoc.application.metrics.MetricsPort metrics;

    // Phase 1 / C6: 用 ConversationProperties.compressThreshold 做 ChatService 内触发判定,
    // 防硬编码 6 但用户改 8 (compress 内部 needsCompression 拿不到更精确的紧致触发).
    // props 只在 conversation.enabled=true 时注入 (@ConditionalOnProperty on properties bean);
    // 多轮路径已用 isMultiTurnEnabled 守门, props null 时 historyCompressor 也 null, 不进本 if.
    @Autowired(required = false)
    private ConversationProperties conversationProperties;

    // Phase 1 / C4 (ADR-0011 §7): 多轮对话 3 件 optional Bean, rag.conversation.enabled=false 时
    // 不注入, 全 null → chat() 完全走 stateless 老路径 (baseline ±3pp gate 不破)
    private ConversationStore conversationStore;
    private QueryContextualizerPort queryContextualizer;
    private PromptAssemblerPort promptAssembler;
    // Phase 1 / C5 (ADR-0011 §5): topic shift 检测器, rag.conversation.topic-shift-detect=true
    // 时注入。null 时视为永远 false (无 shift, 走正常多轮 rewrite)
    private TopicShiftDetectorPort topicShiftDetector;
    // Phase 1 / C6 (ADR-0011 §6 §9): 异步历史压缩器, rag.conversation.compress=true 时注入。
    // null 时不触发压缩 (buffer 大小靠 PromptAssembler MAX=5 硬 cut 单层兜底)
    private HistoryCompressorPort historyCompressor;

    @Autowired(required = false)
    public void setConversationDeps(
            ConversationStore conversationStore,
            QueryContextualizerPort queryContextualizer,
            PromptAssemblerPort promptAssembler) {
        this.conversationStore = conversationStore;
        this.queryContextualizer = queryContextualizer;
        this.promptAssembler = promptAssembler;
        log.info(
                "chat.multi_turn_enabled store={}, rewriter={}, promptAssembler={}",
                conversationStore == null ? "none" : conversationStore.getClass().getSimpleName(),
                queryContextualizer == null ? "none" : "QueryContextualizer",
                promptAssembler == null ? "none" : "PromptAssembler");
    }

    @Autowired(required = false)
    public void setTopicShiftDetector(TopicShiftDetectorPort topicShiftDetector) {
        this.topicShiftDetector = topicShiftDetector;
        log.info("chat.topic_shift_detector_enabled={}", topicShiftDetector != null);
    }

    @Autowired(required = false)
    public void setHistoryCompressor(HistoryCompressorPort historyCompressor) {
        this.historyCompressor = historyCompressor;
        log.info("chat.history_compressor_enabled={}", historyCompressor != null);
    }

    /** Task 7 / V13 Citation Verification: 可选注入 (rag.citation-verifier.enabled=true 时 Bean 才存在)。 */
    @Autowired(required = false)
    private com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort citationVerifier;

    @Autowired(required = false)
    public void setCitationVerifier(
            com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort
                    citationVerifier) {
        this.citationVerifier = citationVerifier;
        log.info("chat.citation_verifier_enabled={}", citationVerifier != null);
    }

    /** Task 7: verifier 配置 (always injected, 默认 disabled)。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.xxx.ragdoc.application.chat.CitationVerifierProperties citationVerifierProperties;

    /** 在线主链统一 Token Budget；同步和 SSE 必须调用同一个 builder。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.xxx.ragdoc.application.chat.pipeline.TokenBudgetContextBuilder tokenBudgetContextBuilder;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xxx.ragdoc.application.chat.pipeline.OnlineExecutionProperties onlineExecutionProperties;

    /** 多轮对话是否启用 (3 件 Bean 全注入才表 enabled, 防 Redis 没起但 flag ON 的不一致)。 */
    private boolean isMultiTurnEnabled() {
        return conversationStore != null && queryContextualizer != null && promptAssembler != null;
    }

    @Transactional
    public ChatResult chat(ChatCommand cmd, TraceId traceId) {
        // 老调用方 (无 conversationId) → stateless 老路径
        return chat(cmd, traceId, null);
    }

    /**
     * Phase 1 / C4 (ADR-0011 §7): 多轮 chat 入口。
     *
     * <p>{@code conversationId} 为 null/blank → stateless 老路径 (baseline 0 变化); 否则 = 多轮模式, load ctx →
     * rewrite → retrieve → LLM → 写回 history (仅 OK turn)。
     */
    @Transactional
    public ChatResult chat(ChatCommand cmd, TraceId traceId, String conversationId) {
        long t0Chat = System.currentTimeMillis(); // Phase 3.A: 同步 chat 总时延 metric 基准
        log.info(
                "chat.start trace_id={}, query_len={}, conv_enabled={}, conv_id={}",
                traceId.value(),
                cmd.query().length(),
                isMultiTurnEnabled(),
                conversationId == null ? "(none)" : conversationId);
        // Phase 1 / C8 (ADR-0011 §11 Observability): trace metadata 加 conversation_id,
        // 让 Langfuse UI 按 conversation 关联多个 trace (同一会话多 turn 的视图)。
        // conversationId 为 null (老调用方 stateless) 时 metadata 加 "none", 不影响其他 trace 查询。
        java.util.Map<String, Object> startTraceMeta = new java.util.HashMap<>();
        startTraceMeta.put("query", cmd.query());
        startTraceMeta.put("conv_enabled", isMultiTurnEnabled());
        startTraceMeta.put("conversation_id", conversationId == null ? "(none)" : conversationId);
        // Task 9 / V15 trace enrichment: user_id (从 V9 AuthContext 拿, 让 Langfuse UI 按 user 关联)
        startTraceMeta.put(
                "user_id", com.xxx.ragdoc.application.auth.AuthContext.currentPrincipal().userId());
        // Task 9: prompt_version + model_version 提前占位 (LLM observation 再覆盖实际值)
        startTraceMeta.put("prompt_version", resolvePromptVersion());
        startTraceMeta.put("model_version", chatClient.currentModel());
        if (isMultiTurnEnabled()
                && conversationProperties != null
                && conversationId != null
                && !conversationId.isBlank()) {
            // 已 load 的 ctx 信息也带进 trace (size/has_summary 是排障关键)
            // 这里只先放 default, ctx 实际 load 后再补 trace 的 enrichment 留 V2 (防复杂度爆炸)
            startTraceMeta.put("compress_threshold", conversationProperties.getCompressThreshold());
        }
        String lfTrace =
                traceObserver.startTrace(
                        traceId.value(),
                        com.xxx.ragdoc.application.auth.AuthContext.currentPrincipal().userId(),
                        startTraceMeta);

        // 1. 限定 doc_id 时校验存在 + READY(4xx 客户端错误走异常)
        if (cmd.docId() != null) {
            Document doc =
                    documentRepository
                            .findById(cmd.docId())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    ErrorCode.DOC_NOT_FOUND,
                                                    "文档不存在: " + cmd.docId()));
            if (doc.status() != DocumentStatus.INDEXED) {
                throw new DomainException(
                        ErrorCode.DOC_NOT_READY,
                        "文档 " + cmd.docId() + " 状态=" + doc.status() + ", 暂不能问答");
            }
        }

        StateHint hint;
        String answer;
        List<ChatResult.Citation> citations = List.of();

        // Phase 1 / C4+C5 (ADR-0011 §5 §7): 加载 ConversationContext + Query rewrite + Topic shift
        // (multi-turn 模式且 conversationId 非空才走; flag OFF 或没 ctx → stateless 老路径)
        ConversationContext ctx = null;
        boolean topicShift = false;
        String retrieveQuery = cmd.query(); // 默认原 query
        ContextualizeResult rewriteResult = null;
        if (isMultiTurnEnabled() && conversationId != null && !conversationId.isBlank()) {
            ctx =
                    conversationStore
                            .findById(conversationId)
                            .orElseGet(
                                    () -> {
                                        // ctx 不存在 / TTL 过期 / store 异常 → 新建空 ctx
                                        // (ConversationStore.findById 内部已 silent fallback)
                                        ConversationContext fresh =
                                                ConversationContext.empty(conversationId);
                                        return fresh;
                                    });
            // Phase 1 / C5: 检测 topic shift (detector 在且 ctx 非首 turn 时才跑)
            // shift=true 时跳过 history rewrite (但不强制 clear ctx, 保留 summary 作弱远期背景)
            if (topicShiftDetector != null) {
                topicShift = topicShiftDetector.isTopicShift(cmd.query(), ctx);
            }
            if (ctx.isEnabled() && !topicShift) {
                rewriteResult = queryContextualizer.contextualize(cmd.query(), ctx.recentTurns());
                retrieveQuery = rewriteResult.retrieveQuery();
            }
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.DECISION,
                    "query.rewrite",
                    cmd.query(),
                    Map.of(
                            "rewritten",
                            retrieveQuery,
                            "outcome",
                            rewriteResult == null ? "skip_topic_shift" : rewriteResult.outcome(),
                            "topic_shift",
                            topicShift),
                    rewriteResult == null ? 0 : rewriteResult.durationMs(),
                    null);
        }
        final String finalRetrieveQuery = retrieveQuery;

        // 2. 决策 EMPTY_KB (无 READY 文档直接兜底, 不进 LLM 不召回不 rewrite — 防浪费)
        // PR-1: 在外层声明 evidence 快照引用, 供 finishAndRecord 落 trace 同步落库 (else 块外引用)。
        com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot evidenceForTrace = null;
        if (documentRepository.countByStatus(DocumentStatus.INDEXED) == 0) {
            hint = StateHint.EMPTY_KB;
            answer = chatMessages.getEmptyKbMessage();
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.DECISION,
                    "decision.empty_kb",
                    null,
                    null,
                    0,
                    null);
        } else {
            // 3. 真实召回(query → embed → Milvus dense ANN → MySQL 回查)
            // Phase 1 / C4: 多轮场景下 retrieve query 是 rewrite 后的 standalone query (LLM prompt 仍用原
            // cmd.query)
            long t0 = System.currentTimeMillis();
            ChatCommand retrieveCmd =
                    finalRetrieveQuery.equals(cmd.query())
                            ? cmd
                            : cmd.withQuery(finalRetrieveQuery);
            RetrieveService.RetrieveResult retrieve = retrieveService.retrieve(retrieveCmd);
            evidenceForTrace = retrieve.evidenceSnapshot();
            long retrieveMs = System.currentTimeMillis() - t0;
            // Task 9 / V15 trace enrichment: 把 retrieved_chunks (id+score) + retrieval_score +
            // rerank
            // _score 全塞进 metadata, 让一次 badcase 在 Langfuse UI 里点链看完整召回细节。
            java.util.Map<String, Object> retrieveMeta = new java.util.HashMap<>();
            retrieveMeta.put("rerank_state", retrieve.rerankState());
            retrieveMeta.put("top1_retrieval_score", retrieve.top1HybridScore());
            retrieveMeta.put("top1_rerank_score", retrieve.top1RerankScore());
            // 把每条 chunk {chunk_id, doc_id, score} 列表化 (snippet 截到 60 字防 trace bloat)
            java.util.List<java.util.Map<String, Object>> chunkMeta = new java.util.ArrayList<>();
            for (RetrieveService.Citation c : retrieve.items()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("chunk_id", c.chunkId());
                m.put("doc_id", c.docId());
                m.put("score", c.score());
                m.put(
                        "snippet",
                        c.snippet() == null
                                ? ""
                                : c.snippet().substring(0, Math.min(60, c.snippet().length())));
                chunkMeta.add(m);
            }
            retrieveMeta.put("retrieved_chunks", chunkMeta);
            // PR-1 / EMS-PR1: 把 Evidence 三段快照的统计 + 唯一 ID 上 Langfuse observation metadata,
            // 让一次 badcase 在 UI 内能映射 trace→evidence→citation 链。tenantId / 全文不进 trace。
            java.util.Map<String, Object> evidenceMeta = new java.util.LinkedHashMap<>();
            com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot snap =
                    retrieve.evidenceSnapshot();
            evidenceMeta.put("rerank_state", snap.rerankState());
            evidenceMeta.put(
                    "initial_count",
                    snap.initialRetrieval() == null ? 0 : snap.initialRetrieval().size());
            evidenceMeta.put(
                    "post_rerank_count", snap.postRerank() == null ? 0 : snap.postRerank().size());
            evidenceMeta.put(
                    "final_context_count",
                    snap.finalContext() == null ? 0 : snap.finalContext().size());
            evidenceMeta.put(
                    "evidence_ids",
                    (snap.finalContext() == null
                            ? java.util.List.<String>of()
                            : snap.finalContext().stream()
                                    .map(
                                            com.xxx.ragdoc.application.chat.evidence.Evidence
                                                    ::evidenceId)
                                    .toList()));
            evidenceMeta.put(
                    "content_hashes",
                    (snap.finalContext() == null
                            ? java.util.List.<String>of()
                            : snap.finalContext().stream()
                                    .map(
                                            com.xxx.ragdoc.application.chat.evidence.Evidence
                                                    ::contentHash)
                                    .toList()));
            retrieveMeta.put("evidence", evidenceMeta);
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.RETRIEVE,
                    "retrieve",
                    cmd.query(),
                    Map.of("hits", retrieve.items().size()),
                    retrieveMs,
                    retrieveMeta);

            if (retrieve.items().isEmpty()) {
                // 3a. NO_RECALL
                hint = StateHint.NO_RECALL;
                answer = chatMessages.getNoRecallMessage();
                traceObserver.observe(
                        lfTrace,
                        TraceObserver.ObservationType.DECISION,
                        "decision.no_recall",
                        null,
                        null,
                        retrieveMs,
                        null);
            } else {
                // 3b. 有召回, 进 LLM; citations 转 ChatResult.Citation
                citations =
                        retrieve.items().stream()
                                .map(
                                        c ->
                                                new ChatResult.Citation(
                                                        c.chunkId(),
                                                        c.docId(),
                                                        c.page(),
                                                        c.snippet(),
                                                        c.llmContext(),
                                                        c.sectionPath()))
                                .toList();
                // P0 修复(citations 错位): history block / LITM 重排 / token+char 预算 / citations
                // 对齐统一收敛到 assembleContextWithHistory — 预算截断掉的 evidence 同步从 citations
                // 移除, 保证 LLM 的 [n] 与前端引用卡片一一对应。
                AssembledContext assembled =
                        assembleContextWithHistory(
                                ctx, topicShift, citations, traceId.value(), lfTrace);
                List<String> context = assembled.context();
                citations = assembled.citations();
                String llmAnswer;
                long t1 = System.currentTimeMillis();
                try {
                    llmAnswer = chatClient.chat(cmd.query(), context);
                } catch (Exception e) {
                    long llmMs = System.currentTimeMillis() - t1;
                    // 3c. LLM_DEGRADED — 召回成功但 LLM 失败, 走降级
                    log.warn(
                            "chat.llm_failed trace_id={}, err={}", traceId.value(), e.getMessage());
                    traceObserver.observe(
                            lfTrace,
                            TraceObserver.ObservationType.LLM,
                            "llm.dashscope",
                            cmd.query(),
                            Map.of("error", e.getClass().getSimpleName()),
                            llmMs,
                            null);
                    traceObserver.observe(
                            lfTrace,
                            TraceObserver.ObservationType.DECISION,
                            "decision.llm_degraded",
                            null,
                            null,
                            llmMs,
                            null);
                    hint = StateHint.LLM_DEGRADED;
                    answer = chatMessages.getLlmDegradedMessage() + traceId.value();
                    // 注意: LLM 降级时 citations 仍返回, 用户可看检索到的片段
                    traceObserver.endTrace(
                            lfTrace,
                            java.util.Map.of(
                                    "state_hint",
                                    hint.name(),
                                    "chat_latency_ms",
                                    System.currentTimeMillis() - t0Chat));
                    return finishAndRecord(
                            cmd,
                            traceId,
                            hint,
                            answer,
                            citations,
                            t0Chat,
                            null,
                            retrieve.evidenceSnapshot());
                }
                long llmMs = System.currentTimeMillis() - t1;
                // Phase 3 / P3-5: 在 llmMs 拿到后立即取 usage (chat() 返回到下一次同步调用之间是窗口)。
                // 仅 OpenAiCompatibleLlmClient 实现; 老 DashScope / NoOp 实现返 empty, 完全后向兼容。
                // Task 9 / V15: usage 同时也写入 LLM observation metadata, 让 Langfuse UI 在 badcase
                // 调试时直接看到 prompt/completion/total token。
                java.util.Optional<ChatClient.TokenUsage> usageOpt = chatClient.lastUsage();
                usageOpt.ifPresent(
                        u -> {
                            metrics.recordTokens(
                                    u.promptTokens(),
                                    u.completionTokens(),
                                    "llm-primary",
                                    chatClient.currentModel());
                            log.debug(
                                    "chat.token_usage trace_id={}, prompt={}, completion={}, total={}",
                                    traceId.value(),
                                    u.promptTokens(),
                                    u.completionTokens(),
                                    u.totalTokens());
                        });
                java.util.Map<String, Object> llmObsMeta = new java.util.HashMap<>();
                llmObsMeta.put("prompt_version", resolvePromptVersion());
                llmObsMeta.put("model_version", chatClient.currentModel());
                llmObsMeta.put("latency_ms", llmMs);
                usageOpt.ifPresent(
                        u -> {
                            java.util.Map<String, Object> tk = new java.util.LinkedHashMap<>();
                            tk.put("prompt", u.promptTokens());
                            tk.put("completion", u.completionTokens());
                            tk.put("total", u.totalTokens());
                            llmObsMeta.put("token_usage", tk);
                        });
                traceObserver.observe(
                        lfTrace,
                        TraceObserver.ObservationType.LLM,
                        "llm.dashscope",
                        cmd.query(),
                        Map.of("answer_len", llmAnswer == null ? 0 : llmAnswer.length()),
                        llmMs,
                        llmObsMeta);
                if (llmAnswer == null || llmAnswer.isBlank() || isLlmRefusal(llmAnswer)) {
                    // LLM_DEGRADED 3 类触发:
                    //   1. llmAnswer null/blank (LLM 直返空)
                    //   2. isLlmRefusal — LLM 返回它的"无相关内容"兜底文案 (OpenAiCompatibleLlmClient prompt
                    //      内嵌 "片段与问题完全无关时回答知识库中没有相关内容" 规则)。
                    // G3 (ADR-0011 §8.2) 实跑教训: 不加 isLlmRefusal 判定时, LLM 拒答文案 state=OK 进 history
                    // → 下次 rewrite LLM 看到 3 条 "知识库中没有相关内容" 当 fact → 污染指代消解 (实跑 6/10 case)
                    hint = StateHint.LLM_DEGRADED;
                    answer = chatMessages.getLlmDegradedMessage() + traceId.value();
                    traceObserver.observe(
                            lfTrace,
                            TraceObserver.ObservationType.DECISION,
                            "decision.llm_blank",
                            null,
                            null,
                            0,
                            null);
                } else {
                    hint = StateHint.OK;
                    answer = llmAnswer;
                    traceObserver.observe(
                            lfTrace,
                            TraceObserver.ObservationType.DECISION,
                            "decision.ok",
                            null,
                            null,
                            0,
                            null);
                }
            }
        }

        // Task 7 / V13 Citation Verification: 在 OK 决定后, 写 history 前插 NLI 核验
        //   - WARN_ONLY: 仅 score 写入 citations.verifyScore, 不改 hint
        //   - REFUSE: score < threshold → hint=VERIFY_FAILED, answer=拒答模板
        //   - REGENERATE: score < threshold → 重新调 LLM (最多 maxRegenerateAttempts 次)
        com.xxx.ragdoc.application.chat.verification.VerificationResult verification = null;
        if (hint == StateHint.OK) {
            verification = runCitationVerification(answer, citations);
            if (verification != null) {
                // 把 scores 写回 citations (供前端/评测观察)
                citations = annotateCitationScores(citations, verification);
                if (verification.outcome()
                        == com.xxx.ragdoc.application.chat.verification.VerificationResult.Outcome
                                .FAIL) {
                    // FAIL 处理: REFUSE / REGENERATE / WARN_ONLY (在 runCitationVerification 内部决策;
                    // 这里只更新 hint)
                    if (verification.errorMessage() == null
                            || !verification.errorMessage().startsWith("WARN_ONLY")) {
                        // runCitationVerification 返 FAIL 且非 WARN_ONLY: hint 改 VERIFY_FAILED
                        // (REGENERATE 已 exhausted, REFUSE 直接拒; WARN_ONLY errorMessage 标
                        // "WARN_ONLY")
                        hint = StateHint.VERIFY_FAILED;
                        if (citationVerifierProperties.getOnFail()
                                == com.xxx.ragdoc.application.chat.CitationVerifierProperties.OnFail
                                        .REFUSE) {
                            answer =
                                    chatMessages.verifierRefusal(
                                            citationVerifierProperties.getScoreThreshold());
                        }
                    }
                }
            }
        }

        // Phase 1 / C4 (ADR-0011 §6 + §8.2 G3): 写回 history — 仅当 OK turn 才允许 (硬 gate 防污染)
        // LLM_DEGRADED / NO_RECALL / EMPTY_KB 一律不写, 否则下游 rewrite 把"出错了"当 fact 污染指代消解
        ConversationContext updatedCtx = null; // 用于 compress 触发判定
        if (hint == StateHint.OK
                && ctx != null
                && isMultiTurnEnabled()
                && conversationId != null
                && !conversationId.isBlank()) {
            try {
                List<Long> citedChunkIds =
                        citations.stream().map(ChatResult.Citation::chunkId).toList();
                Turn thisTurn =
                        new Turn(cmd.query(), answer, citedChunkIds, StateHint.OK, Instant.now());
                updatedCtx = ctx.appendTurn(thisTurn);
                conversationStore.save(updatedCtx);
            } catch (Exception e) {
                // 兜底: store 异常已在 ConversationStore 内 silent log, 此处再防一道 setProperty
                log.warn(
                        "chat.history_write_failed conv_id={}, reason={}",
                        conversationId,
                        e.getMessage());
            }
        }

        // Phase 1 / C6 (ADR-0011 §6 §9): 异步触发压缩 (fire-and-forget)
        // 仅当: ctx 写回成功 + compress 实例注入 + 当前 buffer >= compressThreshold 才走
        // compress 内部 needsCompression 还有双重 check (debounce 1min + size≥threshold), 防 race
        int threshold =
                conversationProperties != null ? conversationProperties.getCompressThreshold() : 6;
        if (updatedCtx != null
                && historyCompressor != null
                && updatedCtx.recentTurns() != null
                && updatedCtx.recentTurns().size() >= threshold) {
            try {
                historyCompressor.compress(conversationId);
            } catch (Exception e) {
                // fire-and-forget 异常不应影响 chat 返回 (DiscardPolicy 队列满也作 silent)
                log.debug(
                        "chat.compress_submit_skipped conv_id={}, reason={}",
                        conversationId,
                        e.getMessage());
            }
        }

        traceObserver.endTrace(
                lfTrace,
                java.util.Map.of(
                        "state_hint",
                        hint.name(),
                        "reason_code",
                        reasonCodeFor(hint),
                        "chat_latency_ms",
                        System.currentTimeMillis() - t0Chat));
        return finishAndRecord(
                cmd, traceId, hint, answer, citations, t0Chat, verification, evidenceForTrace);
    }

    /**
     * V3 W1: 流式 chat。返回 Flux&lt;{@link ChatStreamEvent}&gt; 给 ChatController 的 SSE endpoint。
     *
     * <p>流程:
     *
     * <ol>
     *   <li>同步段复用 {@link #chat} 的 EMPTY_KB / NO_RECALL 判断(docId 校验 + countByStatus + retrieve)
     *   <li>有召回 → 先异步写 chat_traces 短事务(保 feedback 软引用根基)
     *   <li>Flux 发 CitationsEvent(让前端立刻渲染引用卡片)
     *   <li>mergeWith chatClient.chatStream(...) 把每个增量 token 转发为 DeltaEvent
     *   <li>LLM 完整结束后发 DoneEvent(state=OK), 反之 ErrorEvent(state=LLM_DEGRADED)
     * </ol>
     *
     * <p>事务边界: chat_traces 写入用 REQUIRES_NEW 短事务(不持有 Reactor 流), 与 {@link #chat} 设计 一致(都避免长事务)。
     *
     * <p>降级状态: EMPTY_KB / NO_RECALL 同步路径直接发 DoneEvent(不走 LLM)。
     */
    public reactor.core.publisher.Flux<ChatStreamEvent> chatStream(
            ChatCommand cmd, TraceId traceId) {
        // 老调用方 (无 conversationId) → stateless 老路径
        return chatStream(cmd, traceId, null);
    }

    /**
     * P0 修复(SSE 多轮贯通): 多轮流式 chat 入口。
     *
     * <p>此前 SSE 是产品唯一入口但 conversationId 在 ClassicRagPipeline 被丢弃 — load ctx / rewrite /
     * history block / OK turn 写回整套多轮体系在流式路径上是死代码。本重载与同步 {@link #chat(ChatCommand,
     * TraceId, String)} 对齐: conversationId 非空且多轮启用时 load ctx → topic shift → rewrite → 用改写后
     * query 检索 → history block 进 context(不占 [n] 编号) → 流正常结束且非拒答时写回 history。
     */
    public reactor.core.publisher.Flux<ChatStreamEvent> chatStream(
            ChatCommand cmd, TraceId traceId, String conversationId) {
        log.info(
                "chat.stream_start trace_id={}, query_len={}, conv_enabled={}, conv_id={}",
                traceId.value(),
                cmd.query().length(),
                isMultiTurnEnabled(),
                conversationId == null ? "(none)" : conversationId);

        // 1. docId 校验(同 chat 复用 4xx 路径)
        if (cmd.docId() != null) {
            Document doc =
                    documentRepository
                            .findById(cmd.docId())
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    ErrorCode.DOC_NOT_FOUND,
                                                    "文档不存在: " + cmd.docId()));
            if (doc.status() != DocumentStatus.INDEXED) {
                throw new DomainException(
                        ErrorCode.DOC_NOT_READY,
                        "文档 " + cmd.docId() + " 状态=" + doc.status() + ", 暂不能问答");
            }
        }

        // 1b. P0 修复(SSE 多轮贯通): load ctx + topic shift + query rewrite (与 chat() 对齐)。
        // stateless 老调用方 (conversationId=null) 完全不进此分支。
        ConversationContext ctx = null;
        boolean topicShift = false;
        String retrieveQuery = cmd.query();
        if (isMultiTurnEnabled() && conversationId != null && !conversationId.isBlank()) {
            ctx =
                    conversationStore
                            .findById(conversationId)
                            .orElseGet(() -> ConversationContext.empty(conversationId));
            if (topicShiftDetector != null) {
                topicShift = topicShiftDetector.isTopicShift(cmd.query(), ctx);
            }
            if (ctx.isEnabled() && !topicShift) {
                ContextualizeResult rewriteResult =
                        queryContextualizer.contextualize(cmd.query(), ctx.recentTurns());
                retrieveQuery = rewriteResult.retrieveQuery();
            }
        }
        final String finalStreamRetrieveQuery = retrieveQuery;

        // Phase 1.E (2026-08-03): SSE 路径 Langfuse trace 入口
        // Phase 3.A: sseChatT0 = SSE 端到端 latency 基准(EMPTY_KB/NO_RECALL/OK/DEGRADED 全覆盖 via
        // doFinally)
        long sseChatT0 = System.currentTimeMillis();
        java.util.Map<String, Object> sseTraceMeta = new java.util.HashMap<>();
        sseTraceMeta.put("query", cmd.query());
        sseTraceMeta.put("path", "sse");
        sseTraceMeta.put(
                "conversation_id", conversationId == null ? "(none)" : conversationId);
        sseTraceMeta.put(
                "user_id", com.xxx.ragdoc.application.auth.AuthContext.currentPrincipal().userId());
        String lfTrace = traceObserver.startTrace(traceId.value(), null, sseTraceMeta);

        // 2. EMPTY_KB 同步降级: Flux.just(DoneEvent state=EMPTY_KB)
        if (documentRepository.countByStatus(DocumentStatus.INDEXED) == 0) {
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.DECISION,
                    "decision.empty_kb",
                    null,
                    null,
                    0,
                    null);
            traceObserver.endTrace(
                    lfTrace,
                    Map.of(
                            "state_hint",
                            StateHint.EMPTY_KB.name(),
                            "chat_latency_ms",
                            System.currentTimeMillis() - sseChatT0));
            metrics.recordChatTotal(System.currentTimeMillis() - sseChatT0, "skipped");
            return reactor.core.publisher.Flux.just(
                    new ChatStreamEvent.DoneEvent(traceId.value(), StateHint.EMPTY_KB.name()));
        }

        // 3. 召回(同步, retrieve 本身快, p99 < 1s ADR-0004 L1 SLA)
        // P0 修复(SSE 多轮贯通): 多轮场景用 rewrite 后的 standalone query 检索(与 chat() 一致)
        long sseT0 = System.currentTimeMillis(); // retrieve 内部子段(已含 startTrace 后)
        ChatCommand retrieveCmd =
                finalStreamRetrieveQuery.equals(cmd.query())
                        ? cmd
                        : cmd.withQuery(finalStreamRetrieveQuery);
        RetrieveService.RetrieveResult retrieve = retrieveService.retrieve(retrieveCmd);
        long sseRetrieveMs = System.currentTimeMillis() - sseT0;
        traceObserver.observe(
                lfTrace,
                TraceObserver.ObservationType.RETRIEVE,
                "retrieve",
                retrieveCmd.query(),
                Map.of(
                        "hits", retrieve.items().size(),
                        "rerank_state", retrieve.rerankState(),
                        "top1_hybrid_score", retrieve.top1HybridScore(),
                        "top1_rerank_score", retrieve.top1RerankScore()),
                sseRetrieveMs,
                null);

        if (retrieve.items().isEmpty()) {
            // NO_RECALL 同步降级
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.DECISION,
                    "decision.no_recall",
                    null,
                    null,
                    sseRetrieveMs,
                    null);
            traceObserver.endTrace(
                    lfTrace,
                    Map.of(
                            "state_hint",
                            StateHint.NO_RECALL.name(),
                            "chat_latency_ms",
                            System.currentTimeMillis() - sseChatT0));
            metrics.recordChatTotal(System.currentTimeMillis() - sseChatT0, "skipped");
            return reactor.core.publisher.Flux.just(
                    new ChatStreamEvent.DoneEvent(traceId.value(), StateHint.NO_RECALL.name()));
        }

        // 4. 有召回: 拼 citations + context
        // P0 修复(SSE 多轮贯通 + citations 错位): 与 chat() 同一 assembleContextWithHistory —
        // history block 进 context、LITM/budget 对齐 citations。SSE 发给前端的 CitationsEvent
        // 用对齐后的列表, 保证 [n] 与引用卡片一致。
        List<ChatResult.Citation> citations =
                retrieve.items().stream()
                        .map(
                                c ->
                                        new ChatResult.Citation(
                                                c.chunkId(),
                                                c.docId(),
                                                c.page(),
                                                c.snippet(),
                                                c.llmContext(),
                                                c.sectionPath()))
                        .toList();
        AssembledContext assembled =
                assembleContextWithHistory(ctx, topicShift, citations, traceId.value(), lfTrace);
        List<String> context = assembled.context();
        citations = assembled.citations();
        final List<ChatResult.Citation> alignedCitations = citations;
        final ConversationContext streamCtx = ctx;

        // 5. 异步调 LLM 流式; CitationsEvent 先发 → mergeWith LLM delta flux → DoneEvent
        // 注意: chat_traces 不在此处写(写要等 LLM 完整答案长度才知道; 在 chatStream 完成时
        // 由 .doFinally 异步落库, 不阻塞前端 token 流)
        ChatStreamEvent.CitationsEvent head = new ChatStreamEvent.CitationsEvent(citations);

        // 答案累积 StringBuilder(线程安全考虑: Reactor 单线程消费, 无 race)
        StringBuilder acc = new StringBuilder(1024);

        // Phase 3.A: SSE outcome 共享状态(默认 unknown; LLM 流正常结尾 ok, 异常 degraded)
        // 由 stream_done/onErrorResume 设值, doFinally 取值 record total_latency。初始 null 表示上游 cancel
        // 兜底走 degraded。
        java.util.concurrent.atomic.AtomicReference<String> sseOutcome =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        // Phase 1.E: SSE LLM call observation 开始(首 token 前)
        long sseLlmT0 = System.currentTimeMillis();

        reactor.core.publisher.Flux<ChatStreamEvent> tokens =
                chatClient
                        .chatStream(cmd.query(), context)
                        .doOnNext(
                                delta -> {
                                    // 首个 token — 标记 LLM first_token observation
                                    if (acc.length() == 0) {
                                        long firstTokenMs = System.currentTimeMillis() - sseLlmT0;
                                        traceObserver.observe(
                                                lfTrace,
                                                TraceObserver.ObservationType.LLM,
                                                "llm.first_token",
                                                null,
                                                null,
                                                firstTokenMs,
                                                null);
                                        metrics.recordChatFirstToken(firstTokenMs);
                                    }
                                })
                        .map(
                                delta -> {
                                    acc.append(delta);
                                    return (ChatStreamEvent) new ChatStreamEvent.DeltaEvent(delta);
                                })
                        // 关键: OK 终态必须在 onErrorResume 之前接入, 这样 LLM 出错时错误冒泡
                        // 跳过 concatWith(OK defer), 由末尾的 onErrorResume 统一转为唯一的 DEGRADED 终态。
                        // (PR-0 修复: 之前 onErrorResume 在 concatWith 之前, 吞掉错误后 concatWith 仍执行
                        // → 一个流连发 DoneEvent(DEGRADED) + DoneEvent(OK) 两个终态, 违反 SSE 单终态不变量)
                        .concatWith(
                                reactor.core.publisher.Flux.defer(
                                        () -> {
                                            long llmTotalMs = System.currentTimeMillis() - sseLlmT0;
                                            // P1 修复: SSE 路径此前只要流走完就发 OK — LLM 拒答文案
                                            // (isLlmRefusal) / 空答案会以 OK 终态流给用户且污染多轮
                                            // history。与 chat() 的 OK 判定对齐。
                                            String finalAnswer = acc.toString();
                                            boolean streamRefusal =
                                                    finalAnswer.isBlank() || isLlmRefusal(finalAnswer);
                                            StateHint doneState =
                                                    streamRefusal
                                                            ? StateHint.LLM_DEGRADED
                                                            : StateHint.OK;
                                            traceObserver.observe(
                                                    lfTrace,
                                                    TraceObserver.ObservationType.LLM,
                                                    streamRefusal
                                                            ? "llm.stream_refusal"
                                                            : "llm.stream_done",
                                                    null,
                                                    Map.of("answer_len", acc.length()),
                                                    llmTotalMs,
                                                    null);
                                            traceObserver.observe(
                                                    lfTrace,
                                                    TraceObserver.ObservationType.DECISION,
                                                    streamRefusal
                                                            ? "decision.llm_blank"
                                                            : "decision.ok",
                                                    null,
                                                    null,
                                                    llmTotalMs,
                                                    null);
                                            // Phase 3.A: SSE outcome 设 ok/degraded 供 doFinally record
                                            sseOutcome.set(
                                                    streamRefusal ? "degraded" : "ok");
                                            persistTrace(
                                                    cmd,
                                                    traceId,
                                                    finalAnswer,
                                                    doneState,
                                                    retrieve.evidenceSnapshot());
                                            // P0 修复(SSE 多轮贯通): OK turn 写回 history (硬 gate:
                                            // 仅 OK 且非拒答才写, 防污染 rewrite) + 触发异步压缩。
                                            if (!streamRefusal
                                                    && streamCtx != null
                                                    && isMultiTurnEnabled()
                                                    && conversationId != null
                                                    && !conversationId.isBlank()) {
                                                try {
                                                    List<Long> citedChunkIds =
                                                            alignedCitations.stream()
                                                                    .map(
                                                                            ChatResult.Citation
                                                                                    ::chunkId)
                                                                    .toList();
                                                    ConversationContext updatedCtx =
                                                            streamCtx.appendTurn(
                                                                    new Turn(
                                                                            cmd.query(),
                                                                            finalAnswer,
                                                                            citedChunkIds,
                                                                            StateHint.OK,
                                                                            Instant.now()));
                                                    conversationStore.save(updatedCtx);
                                                    int threshold =
                                                            conversationProperties != null
                                                                    ? conversationProperties
                                                                            .getCompressThreshold()
                                                                    : 6;
                                                    if (historyCompressor != null
                                                            && updatedCtx.recentTurns() != null
                                                            && updatedCtx.recentTurns().size()
                                                                    >= threshold) {
                                                        historyCompressor.compress(conversationId);
                                                    }
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "chat.stream_history_write_failed"
                                                                    + " conv_id={}, reason={}",
                                                            conversationId,
                                                            e.getMessage());
                                                }
                                            }
                                            // 流正常结束 → 发 DoneEvent(拒答时 LLM_DEGRADED)
                                            return reactor.core.publisher.Flux.just(
                                                    new ChatStreamEvent.DoneEvent(
                                                            traceId.value(), doneState.name()));
                                        }))
                        .onErrorResume(
                                e -> {
                                    long errMs = System.currentTimeMillis() - sseLlmT0;
                                    log.warn(
                                            "chat.stream_llm_failed trace_id={}, err={}",
                                            traceId.value(),
                                            e.getMessage());
                                    traceObserver.observe(
                                            lfTrace,
                                            TraceObserver.ObservationType.LLM,
                                            "llm.stream_failed",
                                            null,
                                            null,
                                            errMs,
                                            Map.of("error", (Object) e.getMessage()));
                                    traceObserver.observe(
                                            lfTrace,
                                            TraceObserver.ObservationType.DECISION,
                                            "decision.llm_degraded",
                                            null,
                                            null,
                                            errMs,
                                            null);
                                    // Phase 3.A: SSE outcome 设 degraded 供 doFinally record total
                                    sseOutcome.set("degraded");
                                    // LLM 失败时落降级 trace + 发唯一的 DoneEvent(LLM_DEGRADED)
                                    persistTrace(
                                            cmd,
                                            traceId,
                                            acc.toString(),
                                            StateHint.LLM_DEGRADED,
                                            retrieve.evidenceSnapshot());
                                    return reactor.core.publisher.Flux.just(
                                            new ChatStreamEvent.DoneEvent(
                                                    traceId.value(),
                                                    StateHint.LLM_DEGRADED.name()));
                                })
                        // Phase 1.E: 不论成功还是失败, 最后 endTrace。最后一个 observable 派发后 doFinally 在
                        // cancel/complete/error 都触发。
                        .doFinally(
                                signal -> {
                                    StateHint finalHint = StateHint.OK;
                                    try {
                                        String entered = acc.toString();
                                        if (entered.isEmpty()) {
                                            // flux 中途 cancel / onError 都可能 acc 空, 视为 degraded
                                            finalHint = StateHint.LLM_DEGRADED;
                                        }
                                    } catch (Throwable ignore) {
                                        finalHint = StateHint.LLM_DEGRADED;
                                    }
                                    traceObserver.endTrace(
                                            lfTrace,
                                            Map.of(
                                                    "state_hint",
                                                    finalHint.name(),
                                                    "reason_code",
                                                    reasonCodeFor(finalHint),
                                                    "chat_latency_ms",
                                                    System.currentTimeMillis() - sseChatT0));
                                    // Phase 3.A: SSE chat_total_latency。stream_done=ok /
                                    // onErrorResume=degraded 已 set;
                                    // 上游 cancel / acc 空 兜底 degraded。outcome null 时按 finalHint 派生。
                                    String outcome = sseOutcome.get();
                                    if (outcome == null) {
                                        outcome = (finalHint == StateHint.OK) ? "ok" : "degraded";
                                    }
                                    metrics.recordChatTotal(
                                            System.currentTimeMillis() - sseChatT0, outcome);
                                });

        // 异常路径也要落 trace(LLM_DEGRADED 时 acc 包含部分答案; onErrorResume 已转 DoneEvent)
        return reactor.core.publisher.Flux.<ChatStreamEvent>just(head).concatWith(tokens);
    }

    /**
     * 异步落 chat_traces: 流结束/失败时调, 用 REQUIRES_NEW 短事务同 {@link #finish} 设计。
     *
     * <p>PR-1 / EMS-PR1: 同时落 Evidence 快照, 让 SSE 流出的 trace 也能由 trace_id 还原证据。
     */
    private void persistTrace(
            ChatCommand cmd,
            TraceId traceId,
            String answer,
            StateHint hint,
            com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot evidenceSnapshot) {
        try {
            ChatTrace trace =
                    new ChatTrace(
                            traceId,
                            sha256(cmd.query()),
                            cmd.query().length(),
                            answer.length(),
                            hint,
                            null);
            // snapshot=null 时走单参 save (保持 mock 测试兼容); 非 null 走双参 save
            if (evidenceSnapshot == null) {
                chatTracesRepository.save(trace);
            } else {
                chatTracesRepository.save(trace, evidenceSnapshot);
            }
            log.info("chat.stream_end trace_id={}, state_hint={}", traceId.value(), hint);
        } catch (Exception e) {
            // 落库失败绝不阻塞前端流; 只 log
            log.warn(
                    "chat.stream_persist_failed trace_id={}, err={}",
                    traceId.value(),
                    e.getMessage());
        }
    }

    /**
     * P0 修复(citations 错位)统一组装: history block + LITM 重排 + token/char 双闸预算 + citations 对齐。
     *
     * <p>chat() 与 chatStream() 必须共用本方法, 保证两条主路径的 [n] 编号语义一致:
     *
     * <ul>
     *   <li>history block (带 {@code <<CONVERSATION_HISTORY>>} marker) 作为 context 第 1 entry,
     *       LLM client 渲染为不占 [n] 编号的独立段
     *   <li>LITM 重排只作用于 evidence (history 不参与重排), 且 citations 同步重排保持配对
     *   <li>预算截断后 kept 之外的 evidence 从返回的 citations 中移除 — LLM 只能给可见 evidence 标 [n],
     *       前端卡片与 [n] 严格一一对应
     * </ul>
     */
    private AssembledContext assembleContextWithHistory(
            ConversationContext ctx,
            boolean topicShift,
            List<ChatResult.Citation> retrievedCitations,
            String traceId,
            String lfTrace) {
        String historyBlock = "";
        if (ctx != null && ctx.isEnabled() && promptAssembler != null) {
            historyBlock = promptAssembler.buildHistoryBlock(ctx, topicShift);
        }
        final boolean hasHistory = historyBlock != null && !historyBlock.isBlank();

        List<ChatResult.Citation> working = new ArrayList<>(retrievedCitations);
        // Phase 2.A Upgrade A2: Lost-in-the-Middle 重排 (flag-driven, 默认 OFF=baseline 行为)
        // 论文 Liu et al. 2023: LLM 在长 context 中对 [中间位置] 信息提取能力下降。
        // citations 与 context 必须同步重排, 否则开启 LITM 时 [n] 与前端卡片错位。
        if (chatMessages != null && chatMessages.isLitmReorder() && working.size() > 2) {
            working = applyLitmReorderCitations(working);
        }

        List<String> context = new ArrayList<>();
        if (hasHistory) {
            context.add(historyBlock.trim());
        }
        for (var c : working) {
            context.add(c.llmContext());
        }
        context = applyContextBudget(context, traceId, lfTrace);

        int keptEntries = context.size();
        int keptEvidence = hasHistory ? Math.max(0, keptEntries - 1) : keptEntries;
        List<ChatResult.Citation> aligned =
                List.copyOf(working.subList(0, Math.min(keptEvidence, working.size())));
        if (aligned.size() < working.size()) {
            log.info(
                    "chat.citations_aligned_to_budget trace_id={}, kept={}/{}",
                    traceId,
                    aligned.size(),
                    working.size());
        }
        return new AssembledContext(List.copyOf(context), aligned);
    }

    /** {@link #assembleContextWithHistory} 的结果: 对齐后的 context 与 citations。 */
    private record AssembledContext(List<String> context, List<ChatResult.Citation> citations) {}

    /**
     * Phase 2.A Upgrade A2: Lost-in-the-Middle 重排的 citations 配对版本。
     *
     * <p>与 {@link #applyLostInTheMiddleReorder(List)} 同一排列算法, 但作用于 citation 列表 —
     * llmContext 与 citation 同源同序, 排 citation 即排 context。
     */
    static List<ChatResult.Citation> applyLitmReorderCitations(
            List<ChatResult.Citation> sortedDesc) {
        if (sortedDesc == null || sortedDesc.size() <= 2) {
            return sortedDesc;
        }
        int n = sortedDesc.size();
        List<ChatResult.Citation> out = new ArrayList<>(n);
        out.add(sortedDesc.get(0)); // 头: 最高分
        java.util.ArrayDeque<ChatResult.Citation> mid = new java.util.ArrayDeque<>();
        for (int i = 2; i < n; i++) {
            if ((i & 1) == 0) {
                mid.addFirst(sortedDesc.get(i)); // i=2,4,...靠头侧
            } else {
                mid.addLast(sortedDesc.get(i)); // i=3,5,...靠尾侧
            }
        }
        out.addAll(mid);
        out.add(sortedDesc.get(1)); // 尾: 次高分
        return out;
    }

    private List<String> applyContextBudget(List<String> context, String traceId, String lfTrace) {
        int budget = onlineExecutionProperties == null
                ? 3000
                : onlineExecutionProperties.getContextTokenBudget();
        com.xxx.ragdoc.application.chat.pipeline.TokenBudgetContextBuilder builder =
                tokenBudgetContextBuilder == null
                        ? new com.xxx.ragdoc.application.chat.pipeline.TokenBudgetContextBuilder()
                        : tokenBudgetContextBuilder;
        com.xxx.ragdoc.application.chat.pipeline.TokenBudgetContextBuilder.BuildResult built =
                builder.build(
                        context,
                        budget,
                        onlineExecutionProperties == null
                                ? 3800
                                : onlineExecutionProperties.getContextMaxChars());
        traceObserver.observe(
                lfTrace,
                TraceObserver.ObservationType.DECISION,
                "context.token_budget",
                null,
                Map.of(
                        "estimated_tokens", built.estimatedTokens(),
                        "token_budget", built.tokenBudget(),
                        "truncated", built.truncated(),
                        "reason_code",
                        built.truncated()
                                ? com.xxx.ragdoc.application.chat.router.OnlineReasonCode.CONTEXT_TOKEN_BUDGET_APPLIED.name()
                                : "CONTEXT_WITHIN_BUDGET"),
                0,
                null);
        if (built.truncated()) {
            log.info(
                    "chat.context_truncated trace_id={}, estimated_tokens={}, budget={}",
                    traceId,
                    built.estimatedTokens(),
                    built.tokenBudget());
        }
        return built.context();
    }

    private static String reasonCodeFor(StateHint hint) {
        return switch (hint) {
            case OK -> "ANSWER_OK";
            case REFUSED ->
                    com.xxx.ragdoc.application.chat.router.OnlineReasonCode.REFUSE_POLICY.name();
            case EMPTY_KB ->
                    com.xxx.ragdoc.application.chat.router.OnlineReasonCode.EMPTY_KB.name();
            case NO_RECALL ->
                    com.xxx.ragdoc.application.chat.router.OnlineReasonCode.NO_RECALL.name();
            case LLM_DEGRADED ->
                    com.xxx.ragdoc.application.chat.router.OnlineReasonCode.LLM_UNAVAILABLE.name();
            case VERIFY_FAILED ->
                    com.xxx.ragdoc.application.chat.router.OnlineReasonCode.VERIFICATION_FAILED.name();
        };
    }

    /** 共用收尾: 写 chat_traces + 拼 ChatResult.citations, 同事务保证 feedback 软引用合法性根基。 */
    private ChatResult finish(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations) {
        return finish(cmd, traceId, hint, answer, citations, null, null);
    }

    /** Task 7: 重载收尾, 含 verification 透传给 ChatResult. */
    private ChatResult finish(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations,
            com.xxx.ragdoc.application.chat.verification.VerificationResult verification) {
        return finish(cmd, traceId, hint, answer, citations, verification, null);
    }

    /**
     * PR-1 / EMS-PR1: 完整收尾 — 同时落 trace 与真实 Evidence 快照。
     *
     * @param verification Task 7 引用核验结果; nullable
     * @param evidenceSnapshot 本次 chat 实际使用的 Evidence 三段快照; null = NO_RECALL/EMPTY_KB/未启用
     */
    private ChatResult finish(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations,
            com.xxx.ragdoc.application.chat.verification.VerificationResult verification,
            com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot evidenceSnapshot) {
        ChatTrace trace =
                new ChatTrace(
                        traceId,
                        sha256(cmd.query()),
                        cmd.query().length(),
                        answer.length(),
                        hint,
                        null);
        // snapshot=null 时走单参 save — 保持与既有 ChatServiceTest/ConversationStore mock 的兼容
        // (那些测试已 stub save(ChatTrace), 走双参 default delegator 会让 verify 失败)。
        if (evidenceSnapshot == null) {
            chatTracesRepository.save(trace);
        } else {
            chatTracesRepository.save(trace, evidenceSnapshot);
        }
        log.info("chat.end trace_id={}, state_hint={}", traceId.value(), hint);
        return new ChatResult(answer, citations, hint, traceId, verification, evidenceSnapshot);
    }

    /**
     * Phase 3.A: finish + 上调 chat_total_latency metric。
     *
     * <p>outcome tag 规则:
     *
     * <ul>
     *   <li>OK → "ok"
     *   <li>NO_RECALL / EMPTY_KB → "skipped"(不进 LLM, 不算 LLM 失败)
     *   <li>LLM_DEGRADED → "degraded"
     * </ul>
     */
    private ChatResult finishAndRecord(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations,
            long t0Chat) {
        return finishAndRecord(cmd, traceId, hint, answer, citations, t0Chat, null);
    }

    /** Task 7: 重载接受 verification 结果随 response 透传。 */
    private ChatResult finishAndRecord(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations,
            long t0Chat,
            com.xxx.ragdoc.application.chat.verification.VerificationResult verification) {
        return finishAndRecord(cmd, traceId, hint, answer, citations, t0Chat, verification, null);
    }

    /** PR-1 / EMS-PR1: 增加 evidenceSnapshot 透传 — 让 trace + evidence 一同落库, 可由 trace_id 还原。 */
    private ChatResult finishAndRecord(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations,
            long t0Chat,
            com.xxx.ragdoc.application.chat.verification.VerificationResult verification,
            com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot evidenceSnapshot) {
        ChatResult r =
                finish(cmd, traceId, hint, answer, citations, verification, evidenceSnapshot);
        String outcome =
                switch (hint) {
                    case OK -> "ok";
                    case NO_RECALL, EMPTY_KB -> "skipped";
                    case REFUSED -> "refused";
                    case LLM_DEGRADED, VERIFY_FAILED -> "degraded";
                };
        metrics.recordChatTotal(System.currentTimeMillis() - t0Chat, outcome);
        return r;
    }

    /**
     * Phase 1 / C7 G3 修复 (2026-08-04 实跑发现): 检测 LLM 真返回的"无相关内容"拒答文案。
     *
     * <p>问题: OpenAiCompatibleLlmClient / DashScopeChatClient 的 system prompt 内嵌规则 "片段与问题完全无关时,
     * 一句话回答{{知识库中没有相关内容}}"。LLM 偶尔触发 → 看似 state=OK 但 answer 实质是降级文案。G3 抗污染 gate 按 state_hint=OK 写入
     * history → rewrite LLM 把这条当 fact 污染下次 (实跑 6/10 G3 case 失效)。
     *
     * <p>修: 检测 answer ≤ 20 char + 含指定 marker → 视为 LLM_DEGRADED, 不计为 OK turn (G3 拒写 history)。
     *
     * <p>False positive 风险: 真正常回答 ≤20 字 + 含这些 marker 极少 (length gate 守门)。
     */
    // ─── Task 7 / V13 Citation Verification helpers ───────────────────

    /**
     * 运行 citation 验证; 返 null 表示未启用 (properties disabled 或 verifier bean 未注入)。
     *
     * <p>语义: WARN_ONLY 时 FAIL 也返 VerificationResult.outcome=FAIL, errorMessage 标 "WARN_ONLY: ..." 让
     * caller 据此不改 hint; REFUSE/REGENERATE (耗尽) 时 outcome=FAIL + 无 WARN_ONLY 前缀。
     *
     * <p>REGENERATE 暂不真调 LLM 二次 (避免影响 streaming / token accounting 路径), 当作 REFUSE 处理 (javadoc 标注 +
     * properties field 保留以便 Phase 3.B 接入)。
     */
    private com.xxx.ragdoc.application.chat.verification.VerificationResult runCitationVerification(
            String answer, java.util.List<ChatResult.Citation> citations) {
        if (citationVerifier == null
                || citationVerifierProperties == null
                || !citationVerifierProperties.isEnabled()) {
            return null;
        }
        if (answer == null || citations == null || citations.isEmpty()) {
            return null;
        }
        // 把 citations 转成 evidence list (用 llmContext 优先, fallback snippet)
        java.util.List<
                        com.xxx.ragdoc.application.chat.verification.port.CitationVerifierPort
                                .Evidence>
                evidences =
                        citations.stream()
                                .map(
                                        c ->
                                                new com.xxx.ragdoc.application.chat.verification
                                                        .port.CitationVerifierPort.Evidence(
                                                        c.chunkId() == null ? 0L : c.chunkId(),
                                                        c.llmContext() == null
                                                                ? c.snippet()
                                                                : c.llmContext()))
                                .toList();
        com.xxx.ragdoc.application.chat.verification.VerificationResult r =
                citationVerifier.verify(answer, evidences);
        if (r.outcome()
                        == com.xxx.ragdoc.application.chat.verification.VerificationResult.Outcome
                                .FAIL
                && citationVerifierProperties.getOnFail()
                        == com.xxx.ragdoc.application.chat.CitationVerifierProperties.OnFail
                                .WARN_ONLY) {
            // WARN_ONLY: caller 据 errorMessage 不改 hint
            return new com.xxx.ragdoc.application.chat.verification.VerificationResult(
                    r.outcome(),
                    r.overallScore(),
                    r.citationScores(),
                    "WARN_ONLY:fail_score=" + r.overallScore());
        }
        return r;
    }

    /** Task 7: 把 NLI scores 写回每条 citation.verifyScore (无对应 chunkId 的citation留 null)。 */
    private static java.util.List<ChatResult.Citation> annotateCitationScores(
            java.util.List<ChatResult.Citation> citations,
            com.xxx.ragdoc.application.chat.verification.VerificationResult verification) {
        if (citations == null || citations.isEmpty()) return citations;
        if (verification == null
                || verification.citationScores() == null
                || verification.citationScores().isEmpty()) {
            return citations;
        }
        java.util.Map<Long, Double> scoreByChunkId = new java.util.HashMap<>();
        for (var s : verification.citationScores()) {
            scoreByChunkId.put(s.chunkId(), s.score());
        }
        return citations.stream()
                .map(
                        c -> {
                            Double s = c.chunkId() == null ? null : scoreByChunkId.get(c.chunkId());
                            if (s == null) return c;
                            return new ChatResult.Citation(
                                    c.chunkId(),
                                    c.docId(),
                                    c.page(),
                                    c.snippet(),
                                    c.llmContext(),
                                    c.sectionPath(),
                                    s);
                        })
                .toList();
    }

    // ─── Task 9 / V15 Langfuse trace enrichment helpers ──────────────

    /**
     * 解析当前生效的 prompt 模板版本字符串, 给 Langfuse observation 标记。
     *
     * <p>规则: V2 > relaxed > baseline; 与 {@code OpenAiCompatibleLlmClient.buildSystemPrompt} (line
     * 269-271 of LlmClient) 同源语义。
     */
    private String resolvePromptVersion() {
        if (chatMessages != null && chatMessages.isPromptV2()) {
            return chatMessages.isPromptV2Citation() ? "v2-cite" : "v2-plain";
        }
        if (chatMessages != null && chatMessages.isPromptRelaxRefusal()) {
            return "relaxed";
        }
        return "baseline";
    }

    private static boolean isLlmRefusal(String answer) {
        if (answer == null) return false;
        String trimmed = answer.trim();
        // 拒答文案都很短 (≤30 char)
        if (trimmed.length() > 30) return false;
        return trimmed.contains("知识库中没有相关内容")
                || trimmed.contains("未找到相关")
                || trimmed.contains("未在知识库中找到")
                || trimmed.equals("无相关信息")
                || trimmed.equals("(无答案)");
    }

    /** SHA-256 hex 计算; 防 PII 沉淀, 仅存 hash 不存原 query。 */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * Phase 2.A Upgrade A2: Lost-in-the-Middle 重排 (Liu et al. 2023).
     *
     * <p>LLM 在长 context 中间位置提取能力弱于头/尾。把 score 排序后的 context list 重排为: out[0] = sortedDesc[0] (最高分 →
     * 头) out[n-1] = sortedDesc[1] (次高分 → 尾) out[1..n-2] = sortedDesc[2..n-1] 按 odd 交替填充中段,让较高分靠近边界
     *
     * <p>不变性: 输入 size ≤ 2 时直接返回。thread-safe 纯函数。
     */
    static List<String> applyLostInTheMiddleReorder(List<String> sortedDesc) {
        if (sortedDesc == null || sortedDesc.size() <= 2) {
            return sortedDesc;
        }
        int n = sortedDesc.size();
        List<String> out = new ArrayList<>(n);
        out.add(sortedDesc.get(0)); // 头: 最高分
        // 中段: i=2 到 n-1, 偶 i 前插入, 奇 i 后追加 (让 2,3 都靠近头/尾, 较高 i 远离)
        java.util.ArrayDeque<String> mid = new java.util.ArrayDeque<>();
        for (int i = 2; i < n; i++) {
            if ((i & 1) == 0) {
                mid.addFirst(sortedDesc.get(i)); // i=2,4,...靠头侧
            } else {
                mid.addLast(sortedDesc.get(i)); // i=3,5,...靠尾侧
            }
        }
        out.addAll(mid);
        out.add(sortedDesc.get(1)); // 尾: 次高分
        return out;
    }
}
