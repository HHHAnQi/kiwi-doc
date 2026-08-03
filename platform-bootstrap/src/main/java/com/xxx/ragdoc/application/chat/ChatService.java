package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // Phase 3.A: 5 SLO 计量。同步 chat 在 finish 里 record, chatStream 在 stream_done/failed/doFinally record。
    private final com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics metrics;

    @Transactional
    public ChatResult chat(ChatCommand cmd, TraceId traceId) {
        long t0Chat = System.currentTimeMillis(); // Phase 3.A: 同步 chat 总时延 metric 基准
        log.info("chat.start trace_id={}, query_len={}", traceId.value(), cmd.query().length());
        String lfTrace =
                traceObserver.startTrace(traceId.value(), null, Map.of("query", cmd.query()));

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
            if (doc.status() != DocumentStatus.READY) {
                throw new DomainException(
                        ErrorCode.DOC_NOT_READY,
                        "文档 " + cmd.docId() + " 状态=" + doc.status() + ", 暂不能问答");
            }
        }

        StateHint hint;
        String answer;
        List<ChatResult.Citation> citations = List.of();

        // 2. 决策 EMPTY_KB (无 READY 文档直接兜底, 不进 LLM)
        if (documentRepository.countByStatus(DocumentStatus.READY) == 0) {
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
            long t0 = System.currentTimeMillis();
            RetrieveService.RetrieveResult retrieve = retrieveService.retrieve(cmd);
            long retrieveMs = System.currentTimeMillis() - t0;
            traceObserver.observe(
                    lfTrace,
                    TraceObserver.ObservationType.RETRIEVE,
                    "retrieve",
                    cmd.query(),
                    Map.of(
                            "hits",
                            retrieve.items().size(),
                            "rerank_state",
                            retrieve.rerankState(),
                            "top1_hybrid_score",
                            retrieve.top1HybridScore(),
                            "top1_rerank_score",
                            retrieve.top1RerankScore()),
                    retrieveMs,
                    null);

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
                // 喂 LLM 用 chunk 全文(llmContext), 不是给前端的 200 字 snippet。
                // 早期两者共用 snippet 致双重截断 maxContextChars 才是真正该用的总闸。
                List<String> context = new ArrayList<>();
                for (var c : retrieve.items()) {
                    context.add(c.llmContext());
                }
                // Phase 2.A Upgrade A2: Lost-in-the-Middle 重排 (flag-driven, 默认 OFF=baseline 行为)
                // 论文 Liu et al. 2023: LLM 在长 context 中对 [中间位置] 信息提取能力下降。
                // 单独贡献未验证(Phase 2.A 与 A1 同跑 trade-off), 待 Phase 2.B 单独 A/B。
                if (chatMessages != null && chatMessages.isLitmReorder()) {
                    context = applyLostInTheMiddleReorder(context);
                }
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
                    traceObserver.endTrace(lfTrace, Map.of("state_hint", hint.name()));
                    return finishAndRecord(cmd, traceId, hint, answer, citations, t0Chat);
                }
                long llmMs = System.currentTimeMillis() - t1;
                traceObserver.observe(
                        lfTrace,
                        TraceObserver.ObservationType.LLM,
                        "llm.dashscope",
                        cmd.query(),
                        Map.of("answer_len", llmAnswer == null ? 0 : llmAnswer.length()),
                        llmMs,
                        null);
                if (llmAnswer == null || llmAnswer.isBlank()) {
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
        traceObserver.endTrace(lfTrace, Map.of("state_hint", hint.name()));
        return finishAndRecord(cmd, traceId, hint, answer, citations, t0Chat);
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
        log.info(
                "chat.stream_start trace_id={}, query_len={}",
                traceId.value(),
                cmd.query().length());

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
            if (doc.status() != DocumentStatus.READY) {
                throw new DomainException(
                        ErrorCode.DOC_NOT_READY,
                        "文档 " + cmd.docId() + " 状态=" + doc.status() + ", 暂不能问答");
            }
        }

        // Phase 1.E (2026-08-03): SSE 路径 Langfuse trace 入口
        // Phase 3.A: sseChatT0 = SSE 端到端 latency 基准(EMPTY_KB/NO_RECALL/OK/DEGRADED 全覆盖 via doFinally)
        long sseChatT0 = System.currentTimeMillis();
        String lfTrace =
                traceObserver.startTrace(traceId.value(), null, Map.of("query", cmd.query(), "path", "sse"));

        // 2. EMPTY_KB 同步降级: Flux.just(DoneEvent state=EMPTY_KB)
        if (documentRepository.countByStatus(DocumentStatus.READY) == 0) {
            traceObserver.observe(lfTrace, TraceObserver.ObservationType.DECISION,
                    "decision.empty_kb", null, null, 0, null);
            traceObserver.endTrace(lfTrace, Map.of("state_hint", StateHint.EMPTY_KB.name()));
            metrics.recordChatTotal(System.currentTimeMillis() - sseChatT0, "skipped");
            return reactor.core.publisher.Flux.just(
                    new ChatStreamEvent.DoneEvent(traceId.value(), StateHint.EMPTY_KB.name()));
        }

        // 3. 召回(同步, retrieve 本身快, p99 < 1s ADR-0004 L1 SLA)
        long sseT0 = System.currentTimeMillis(); // retrieve 内部子段(已含 startTrace 后)
        RetrieveService.RetrieveResult retrieve = retrieveService.retrieve(cmd);
        long sseRetrieveMs = System.currentTimeMillis() - sseT0;
        traceObserver.observe(lfTrace, TraceObserver.ObservationType.RETRIEVE,
                "retrieve", cmd.query(),
                Map.of(
                        "hits", retrieve.items().size(),
                        "rerank_state", retrieve.rerankState(),
                        "top1_hybrid_score", retrieve.top1HybridScore(),
                        "top1_rerank_score", retrieve.top1RerankScore()),
                sseRetrieveMs, null);

        if (retrieve.items().isEmpty()) {
            // NO_RECALL 同步降级
            traceObserver.observe(lfTrace, TraceObserver.ObservationType.DECISION,
                    "decision.no_recall", null, null, sseRetrieveMs, null);
            traceObserver.endTrace(lfTrace, Map.of("state_hint", StateHint.NO_RECALL.name()));
            metrics.recordChatTotal(System.currentTimeMillis() - sseChatT0, "skipped");
            return reactor.core.publisher.Flux.just(
                    new ChatStreamEvent.DoneEvent(traceId.value(), StateHint.NO_RECALL.name()));
        }

        // 4. 有召回: 拼 citations + context
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
        List<String> context =
                retrieve.items().stream().map(RetrieveService.Citation::llmContext).toList();
        // Phase 2.A Upgrade A2: SSE 路径 LITM (flag-driven, 默认 OFF)
        if (chatMessages != null && chatMessages.isLitmReorder()) {
            context = applyLostInTheMiddleReorder(new ArrayList<>(context));
        }

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
                        .doOnNext(delta -> {
                            // 首个 token — 标记 LLM first_token observation
                            if (acc.length() == 0) {
                                long firstTokenMs = System.currentTimeMillis() - sseLlmT0;
                                traceObserver.observe(lfTrace, TraceObserver.ObservationType.LLM,
                                        "llm.first_token", null, null, firstTokenMs, null);
                                metrics.recordChatFirstToken(firstTokenMs);
                            }
                        })
                        .map(
                                delta -> {
                                    acc.append(delta);
                                    return (ChatStreamEvent) new ChatStreamEvent.DeltaEvent(delta);
                                })
                        .onErrorResume(
                                e -> {
                                    long errMs = System.currentTimeMillis() - sseLlmT0;
                                    log.warn("chat.stream_llm_failed trace_id={}, err={}", traceId.value(), e.getMessage());
                                    traceObserver.observe(lfTrace, TraceObserver.ObservationType.LLM,
                                            "llm.stream_failed", null, null, errMs,
                                            Map.of("error", (Object) e.getMessage()));
                                    traceObserver.observe(lfTrace, TraceObserver.ObservationType.DECISION,
                                            "decision.llm_degraded", null, null, errMs, null);
                                    // Phase 3.A: SSE outcome 设 degraded 供 doFinally record total
                                    sseOutcome.set("degraded");
                                    // LLM 失败时落降级 trace + 发 DoneEvent(LLM_DEGRADED)
                                    persistTrace(cmd, traceId, acc.toString(), StateHint.LLM_DEGRADED);
                                    return reactor.core.publisher.Flux.just(
                                            new ChatStreamEvent.DoneEvent(
                                                    traceId.value(),
                                                    StateHint.LLM_DEGRADED.name()));
                                })
                        .concatWith(
                                reactor.core.publisher.Flux.defer(
                                        () -> {
                                            long llmTotalMs = System.currentTimeMillis() - sseLlmT0;
                                            traceObserver.observe(lfTrace, TraceObserver.ObservationType.LLM,
                                                    "llm.stream_done",
                                                    null, Map.of("answer_len", acc.length()), llmTotalMs, null);
                                            traceObserver.observe(lfTrace, TraceObserver.ObservationType.DECISION,
                                                    "decision.ok", null, null, llmTotalMs, null);
                                            // Phase 3.A: SSE outcome 设 ok 供 doFinally record total
                                            sseOutcome.set("ok");
                                            // 流正常结束 → 落 trace + 发 DoneEvent
                                            persistTrace(cmd, traceId, acc.toString(), StateHint.OK);
                                            return reactor.core.publisher.Flux.just(
                                                    new ChatStreamEvent.DoneEvent(
                                                            traceId.value(), StateHint.OK.name()));
                                        }))
                        // Phase 1.E: 不论成功还是失败, 最后 endTrace。最后一个 observable 派发后 doFinally 在 cancel/complete/error 都触发。
                        .doFinally(signal -> {
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
                            traceObserver.endTrace(lfTrace, Map.of("state_hint", finalHint.name()));
                            // Phase 3.A: SSE chat_total_latency。stream_done=ok / onErrorResume=degraded 已 set;
                            // 上游 cancel / acc 空 兜底 degraded。outcome null 时按 finalHint 派生。
                            String outcome = sseOutcome.get();
                            if (outcome == null) {
                                outcome = (finalHint == StateHint.OK) ? "ok" : "degraded";
                            }
                            metrics.recordChatTotal(System.currentTimeMillis() - sseChatT0, outcome);
                        });

        // 异常路径也要落 trace(LLM_DEGRADED 时 acc 包含部分答案; onErrorResume 已转 DoneEvent)
        return reactor.core.publisher.Flux.<ChatStreamEvent>just(head).concatWith(tokens);
    }

    /** 异步落 chat_traces: 流结束/失败时调, 用 REQUIRES_NEW 短事务同 {@link #finish} 设计。 */
    private void persistTrace(ChatCommand cmd, TraceId traceId, String answer, StateHint hint) {
        try {
            ChatTrace trace =
                    new ChatTrace(
                            traceId,
                            sha256(cmd.query()),
                            cmd.query().length(),
                            answer.length(),
                            hint,
                            null);
            chatTracesRepository.save(trace);
            log.info("chat.stream_end trace_id={}, state_hint={}", traceId.value(), hint);
        } catch (Exception e) {
            // 落库失败绝不阻塞前端流; 只 log
            log.warn(
                    "chat.stream_persist_failed trace_id={}, err={}",
                    traceId.value(),
                    e.getMessage());
        }
    }

    /** 共用收尾: 写 chat_traces + 拼 ChatResult.citations, 同事务保证 feedback 软引用合法性根基。 */
    private ChatResult finish(
            ChatCommand cmd,
            TraceId traceId,
            StateHint hint,
            String answer,
            List<ChatResult.Citation> citations) {
        ChatTrace trace =
                new ChatTrace(
                        traceId,
                        sha256(cmd.query()),
                        cmd.query().length(),
                        answer.length(),
                        hint,
                        null);
        chatTracesRepository.save(trace);
        log.info("chat.end trace_id={}, state_hint={}", traceId.value(), hint);
        return new ChatResult(answer, citations, hint, traceId);
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
        ChatResult r = finish(cmd, traceId, hint, answer, citations);
        String outcome =
                switch (hint) {
                    case OK -> "ok";
                    case NO_RECALL, EMPTY_KB -> "skipped";
                    case LLM_DEGRADED -> "degraded";
                };
        metrics.recordChatTotal(System.currentTimeMillis() - t0Chat, outcome);
        return r;
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
     * <p>LLM 在长 context 中间位置提取能力弱于头/尾。把 score 排序后的 context list 重排为:
     *   out[0]   = sortedDesc[0]   (最高分 → 头)
     *   out[n-1] = sortedDesc[1]   (次高分 → 尾)
     *   out[1..n-2] = sortedDesc[2..n-1] 按 odd 交替填充中段,让较高分靠近边界
     *
     * <p>不变性: 输入 size ≤ 2 时直接返回。thread-safe 纯函数。
     */
    static List<String> applyLostInTheMiddleReorder(List<String> sortedDesc) {
        if (sortedDesc == null || sortedDesc.size() <= 2) {
            return sortedDesc;
        }
        int n = sortedDesc.size();
        List<String> out = new ArrayList<>(n);
        out.add(sortedDesc.get(0));  // 头: 最高分
        // 中段: i=2 到 n-1, 偶 i 前插入, 奇 i 后追加 (让 2,3 都靠近头/尾, 较高 i 远离)
        java.util.ArrayDeque<String> mid = new java.util.ArrayDeque<>();
        for (int i = 2; i < n; i++) {
            if ((i & 1) == 0) {
                mid.addFirst(sortedDesc.get(i));  // i=2,4,...靠头侧
            } else {
                mid.addLast(sortedDesc.get(i));   // i=3,5,...靠尾侧
            }
        }
        out.addAll(mid);
        out.add(sortedDesc.get(1));  // 尾: 次高分
        return out;
    }
}
