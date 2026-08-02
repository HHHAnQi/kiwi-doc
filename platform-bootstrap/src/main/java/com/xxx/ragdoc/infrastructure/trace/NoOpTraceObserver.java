package com.xxx.ragdoc.infrastructure.trace;

import com.xxx.ragdoc.application.chat.port.TraceObserver;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link TraceObserver} 默认 No-op 实现: 当 Langfuse 未启用(langfuse.enabled=false), 本 bean 兜底, ChatService
 * 注入它后所有上报接口零开销(empty 方法体)。
 *
 * <p>设计意图: ChatService 永远注入 TraceObserver, 但具体实现可能是 NoOpTraceObserver 或 LangfuseTraceObserver, 由
 * {@link ConditionalOnMissingBean} 决定装配顺序。
 */
@Slf4j
@Component
@ConditionalOnMissingBean(TraceObserver.class)
public class NoOpTraceObserver implements TraceObserver {

    @Override
    public String startTrace(String chatTraceId, String userId, Map<String, Object> metadata) {
        // 返回原 chatTraceId 占位, 让 ChatService 内部 traceId 链路对齐
        return chatTraceId;
    }

    @Override
    public void observe(
            String traceId,
            ObservationType type,
            String name,
            Object input,
            Object output,
            long durationMs,
            Map<String, Object> metadata) {
        // no-op
    }

    @Override
    public void endTrace(String traceId, Map<String, Object> finalMetadata) {
        // no-op
    }
}
