package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.chat.port.ChatTracesRepository;
import com.xxx.ragdoc.domain.chat.ChatTrace;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChatTraceEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.ChatTraceJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** {@link ChatTracesRepository} 端口的 JPA 适配实现。 负责 domain.ChatTrace ↔ ChatTraceEntity 翻译。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaChatTracesRepository implements ChatTracesRepository {

    private final ChatTraceJpaRepository jpa;

    @Override
    public ChatTrace save(ChatTrace chatTrace) {
        ChatTraceEntity e = new ChatTraceEntity();
        e.setTraceId(chatTrace.traceId().value());
        e.setQueryHash(chatTrace.queryHash());
        e.setQueryLen(chatTrace.queryLen());
        e.setAnswerLen(chatTrace.answerLen());
        e.setStateHint(chatTrace.stateHint().name());
        ChatTraceEntity saved = jpa.save(e);
        log.debug(
                "chat_trace saved trace_id={}, state_hint={}",
                saved.getTraceId(),
                saved.getStateHint());
        return toDomain(saved);
    }

    @Override
    public boolean existsByTraceId(String traceId) {
        return jpa.existsByTraceId(traceId);
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
