package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChatTraceEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.ChatTraceJpaRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** {@link ChatTracesRepository} 端口的 JPA 适配实现。 负责 domain.ChatTrace ↔ ChatTraceEntity 翻译。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaChatTracesRepository implements ChatTracesRepository {

    // PR-1: ObjectMapper bean 由 Spring Boot starter 注入; 序列化 EvidenceSnapshot 为 JSON 列。
    // 用组合而非继承, 避免对 JpaChatTracesRepository 单测加 Jackson 强依赖。
    private final ChatTraceJpaRepository jpa;
    private final ObjectMapper objectMapper;

    @Override
    public ChatTrace save(ChatTrace chatTrace) {
        return save(chatTrace, null);
    }

    @Override
    public ChatTrace save(ChatTrace chatTrace, EvidenceSnapshot evidenceSnapshot) {
        ChatTraceEntity e = new ChatTraceEntity();
        e.setTraceId(chatTrace.traceId().value());
        e.setQueryHash(chatTrace.queryHash());
        e.setQueryLen(chatTrace.queryLen());
        e.setAnswerLen(chatTrace.answerLen());
        e.setStateHint(chatTrace.stateHint().name());
        if (evidenceSnapshot != null) {
            try {
                e.setEvidenceSnapshot(objectMapper.writeValueAsString(evidenceSnapshot));
            } catch (JsonProcessingException ex) {
                // 序列化失败不阻塞 chat 主流程, 只 log — 与既有 "落 trace 失败只 log" 风格一致。
                log.warn(
                        "chat_trace.evidence_serialize_failed trace_id={}, err={}",
                        chatTrace.traceId().value(),
                        ex.getMessage());
            }
        }
        // 若已有同 trace_id 行 (SSE 同 trace 被异步落两次的边界), 保留上次 evidence 不被 null 覆盖。
        if (evidenceSnapshot == null) {
            jpa.findById(chatTrace.traceId().value())
                    .ifPresent(prev -> e.setEvidenceSnapshot(prev.getEvidenceSnapshot()));
        }
        ChatTraceEntity saved = jpa.save(e);
        log.debug(
                "chat_trace saved trace_id={}, state_hint={}, evidence={}",
                saved.getTraceId(),
                saved.getStateHint(),
                saved.getEvidenceSnapshot() == null ? "none" : "present");
        return toDomain(saved);
    }

    @Override
    public boolean existsByTraceId(String traceId) {
        return jpa.existsByTraceId(traceId);
    }

    @Override
    public Optional<EvidenceSnapshot> findEvidenceByTraceId(String traceId) {
        return jpa.findById(traceId)
                .map(ChatTraceEntity::getEvidenceSnapshot)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(
                        json -> {
                            try {
                                return Optional.of(
                                        objectMapper.readValue(json, EvidenceSnapshot.class));
                            } catch (JsonProcessingException ex) {
                                log.warn(
                                        "chat_trace.evidence_deserialize_failed trace_id={}, err={}",
                                        traceId,
                                        ex.getMessage());
                                return Optional.empty();
                            }
                        });
    }

    private static ChatTrace toDomain(ChatTraceEntity e) {
        return new ChatTrace(
                new TraceId(e.getTraceId()),
                e.getQueryHash(),
                e.getQueryLen(),
                e.getAnswerLen(),
                StateHint.valueOf(e.getStateHint()),
                e.getCreatedAt());
    }
}
