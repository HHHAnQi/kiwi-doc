package com.xxx.ragdoc.application.chat.port;

import java.util.Map;

/**
 * 可观测 trace 端口(V3-W3 Langfuse 接入, spec DoD-5)。
 *
 * <p>chat-app ChatService 在关键步骤上报 trace(检索 / LLM / state_hint 决策); 默认 no-op 实现 在 Langfuse 未启用时零开销,
 * 启用时走 HTTP ingestion 调 Langfuse 服务。
 *
 * <p>语义参考 Langfuse: 一个 Trace 一次 chat 会话, 内含多个 Observation(子事件, 类似 OTel span):
 *
 * <ul>
 *   <li>{@link ObservationType#RETRIEVE} 召回子事件(含 hits, scores)
 *   <li>{@link ObservationType#LLM} LLM 调用子事件(含 prompt, completion,latency)
 *   <li>{@link ObservationType#DECISION} state_hint 决策点(EMPTY_KB/NO_RECALL/OK)
 * </ul>
 *
 * <p>所有方法都不抛异常 — trace 失败应静默, 不能影响 chat 主路径。
 */
public interface TraceObserver {

    /** 开始一次 chat 会话 trace, 返回 traceId(对应 Langfuse trace.id)。 */
    String startTrace(String chatTraceId, String userId, Map<String, Object> metadata);

    /**
     * 上报一个子事件 observation。
     *
     * @param traceId 来自 startTrace
     * @param type 事件类型
     * @param name 事件名(如 "retrieve.hybrid", "llm.dashscope")
     * @param input 输入(query / prompt 等)
     * @param output 输出(hits / completion 等)
     * @param durationMs 持续(ms)
     * @param metadata 额外元数据
     */
    void observe(
            String traceId,
            ObservationType type,
            String name,
            Object input,
            Object output,
            long durationMs,
            Map<String, Object> metadata);

    /** 结束一次 trace(标记完成 + 写入总耗时 / state_hint / 最终 trace 字段)。 */
    void endTrace(String traceId, Map<String, Object> finalMetadata);

    /** Langfuse 风格的 observation 类型。 */
    enum ObservationType {
        RETRIEVE,
        LLM,
        RERANK,
        DECISION,
        EMBED
    }
}
