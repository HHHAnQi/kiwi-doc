package com.xxx.ragdoc.infrastructure.trace;

import com.xxx.ragdoc.application.chat.port.TraceObserver;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * {@link TraceObserver} 默认 No-op 实现: 当 Langfuse 未启用(langfuse.enabled=false 或缺), 本 bean 兜底,
 * ChatService 注入它后所有上报接口零开销(empty 方法体)。
 *
 * <p>设计意图: ChatService 永远注入 TraceObserver, 但具体实现可能是 NoOpTraceObserver 或 LangfuseTraceObserver。
 *
 * <p>装配策略修正: 用 {@link ConditionalOnMissingBean} 时 Spring Boot 在某些场景下无法在 chat-app 启动 早期判定 bean
 * 是否存在(顺序问题), 启动会抛 NoUniqueBeanDefinition 但 ChatService 在 application 层 拿不到 bean 导致启动 fail. 这里改成与
 * LangfuseTraceObserver 互斥的相反条件:
 *
 * <ul>
 *   <li>本 bean {@code @ConditionalOnProperty(langfuse.enabled, havingValue="false",
 *       matchIfMissing=true)}
 *   <li>{@link LangfuseTraceObserver} {@code @ConditionalOnProperty(langfuse.enabled,
 *       havingValue="true")}
 * </ul>
 *
 * <p>这样两个 bean 永远只会有一个装配, 启动期判定也简单。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "langfuse",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
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
