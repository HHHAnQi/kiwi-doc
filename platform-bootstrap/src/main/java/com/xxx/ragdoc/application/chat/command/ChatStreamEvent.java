package com.xxx.ragdoc.application.chat.command;

import java.util.List;

/**
 * V3 W1: SSE 事件基类。Controller 把每个事件转成 SSE 单行格式发给前端。
 *
 * <p>设计意图(让前端能"先显示引用 → 边收 token 边显示答案"):
 *
 * <ul>
 *   <li>{@link CitationsEvent} 紧接 DELTA 之前一次性发完(前端渲染引用卡片)
 *   <li>{@link DeltaEvent} 一个 = 一个增量 token 片段, 累积渲染答案
 *   <li>{@link DoneEvent} 终止信号(含 traceId 给前端做反馈入口)
 *   <li>{@link ErrorEvent} 错误事件(替代 LLM_DEGRADED 200 同步路径)
 * </ul>
 *
 * <p>sealed + record Java 17, 让 switch exhaustive 编译期保证完整覆盖。
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.CitationsEvent,
                ChatStreamEvent.DeltaEvent,
                ChatStreamEvent.DoneEvent,
                ChatStreamEvent.ErrorEvent {

    /** 事件类型字符串(发到 SSE event: 字段)。 */
    String type();

    /**
     * 召回结果一次性发出。前端拿到后渲染引用卡片 + 等待 LLM token。
     *
     * @param citations 引用列表
     */
    record CitationsEvent(List<ChatResult.Citation> citations) implements ChatStreamEvent {
        @Override
        public String type() {
            return "citations";
        }
    }

    /**
     * LLM 增量 token 片段。前端累积拼接渲染。
     *
     * @param delta token 文本片段
     */
    record DeltaEvent(String delta) implements ChatStreamEvent {
        @Override
        public String type() {
            return "delta";
        }
    }

    /**
     * 流正常终止。含 traceId 供前端做反馈入口 + 最终 stateHint(EMPTY_KB/NO_RECALL/LLM_DEGRADED/OK)。 P2-D5(C):
     * 与同步响应头一致的 correlation 语义 — runId/terminalStatus/decisionSummary 可空(非 Agent 路径或 run 未创建时),
     * 只在真实存在时携带(不造 fake)。
     */
    record DoneEvent(
            String traceId,
            String stateHint,
            String reasonCode,
            String runId,
            String terminalStatus,
            String decisionSummary)
            implements ChatStreamEvent {
        public DoneEvent(String traceId, String stateHint, String reasonCode) {
            this(traceId, stateHint, reasonCode, null, null, null);
        }

        public DoneEvent(String traceId, String stateHint) {
            this(traceId, stateHint, null, null, null, null);
        }

        @Override
        public String type() {
            return "done";
        }
    }

    /** 流异常终止(LLM 调用中途抛错)。前端收到后显示降级提示。P2-D5(C): 可选携带真实 runId。 */
    record ErrorEvent(String traceId, String message, String runId) implements ChatStreamEvent {
        public ErrorEvent(String traceId, String message) {
            this(traceId, message, null);
        }

        @Override
        public String type() {
            return "error";
        }
    }
}
