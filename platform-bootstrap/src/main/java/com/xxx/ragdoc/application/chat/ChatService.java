package com.xxx.ragdoc.application.chat;

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
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat 用例(V1 stub 版本)。
 *
 * <p>V1 行为锁定(第 12 轮补缺, 不可翻案):
 *
 * <ul>
 *   <li>不调 Embedding / Milvus 召回 / LLM
 *   <li>0 个 READY 文档 → EMPTY_KB
 *   <li>≥1 READY 文档 → NO_RECALL(stub 无 chunks)
 *   <li>失败永不抛异常(除 4xx 客户端错误外)
 * </ul>
 *
 * <p>trace_id 贯穿: 每次调用写一条 chat_traces 记录(与响应同事务), feedback 通过 trace_id 软引用此记录(ADR-0003)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final DocumentRepository documentRepository;
    private final ChatTracesRepository chatTracesRepository;
    private final ChatMessages chatMessages;

    @Transactional
    public ChatResult chat(ChatCommand cmd, TraceId traceId) {
        log.info("chat.start trace_id={}, query_len={}", traceId.value(), cmd.query().length());

        // 1. 限定 doc_id 时校验存在 + READY
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

        // 2. 决策优先级: EMPTY_KB > NO_RECALL > OK/LLM_DEGRADED
        StateHint hint;
        String answer;
        if (documentRepository.countByStatus(DocumentStatus.READY) == 0) {
            hint = StateHint.EMPTY_KB;
            answer = chatMessages.getEmptyKbMessage();
        } else {
            // V1 stub: parser 未产 chunks, 永远走 NO_RECALL
            // V2 接真实召回后: 这里改调 retrieveService, 根据召回结果判 NO_RECALL / 调 LLM
            hint = StateHint.NO_RECALL;
            answer = chatMessages.getNoRecallMessage();
        }

        // 3. 写 chat_traces(与响应同事务, feedback 软引用合法性根基)
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
        return ChatResult.of(hint, answer, traceId);
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
