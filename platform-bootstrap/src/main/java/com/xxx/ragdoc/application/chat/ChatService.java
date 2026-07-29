package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
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

    @Transactional
    public ChatResult chat(ChatCommand cmd, TraceId traceId) {
        log.info("chat.start trace_id={}, query_len={}", traceId.value(), cmd.query().length());

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
        } else {
            // 3. 真实召回(query → embed → Milvus dense ANN → MySQL 回查)
            RetrieveService.RetrieveResult retrieve = retrieveService.retrieve(cmd);
            if (retrieve.items().isEmpty()) {
                // 3a. NO_RECALL
                hint = StateHint.NO_RECALL;
                answer = chatMessages.getNoRecallMessage();
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
                                                        c.snippet()))
                                .toList();
                // 拼 context 给 LLM(用 snippet 已够, 不必塞原文)
                List<String> context = new ArrayList<>();
                for (var c : retrieve.items()) {
                    context.add(c.snippet());
                }
                String llmAnswer;
                try {
                    llmAnswer = chatClient.chat(cmd.query(), context);
                } catch (Exception e) {
                    // 3c. LLM_DEGRADED — 召回成功但 LLM 失败, 走降级
                    log.warn(
                            "chat.llm_failed trace_id={}, err={}", traceId.value(), e.getMessage());
                    hint = StateHint.LLM_DEGRADED;
                    answer = chatMessages.getLlmDegradedMessage() + traceId.value();
                    // 注意: LLM 降级时 citations 仍返回, 用户可看检索到的片段
                    return finish(cmd, traceId, hint, answer, citations);
                }
                if (llmAnswer == null || llmAnswer.isBlank()) {
                    hint = StateHint.LLM_DEGRADED;
                    answer = chatMessages.getLlmDegradedMessage() + traceId.value();
                } else {
                    hint = StateHint.OK;
                    answer = llmAnswer;
                }
            }
        }

        return finish(cmd, traceId, hint, answer, citations);
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
}
