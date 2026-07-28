package com.xxx.ragdoc.domain.chat;

import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.time.Instant;

/**
 * 一次 chat 调用的可追溯记录(V1 仅存 hash 与长度, 不存原 query/answer)。
 *
 * <p>与 {@code chat_traces} 表对应, 见 V2__add_chat_traces.sql。 feedback 通过 trace_id 软引用此聚合, 见 ADR-0003。
 *
 * <p>不变量:
 *
 * <ul>
 *   <li>{@link TraceId} 必填, 格式 [A-Za-z0-9_-]{1,64}
 *   <li>{@code queryHash} 为 SHA-256(64 位 hex)
 *   <li>{@link StateHint} 必填, 与 chat 响应的 state_hint 字段保持一致
 * </ul>
 */
public record ChatTrace(
        TraceId traceId,
        String queryHash,
        int queryLen,
        Integer answerLen,
        StateHint stateHint,
        Instant createdAt) {
    public ChatTrace {
        if (traceId == null) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        if (queryHash == null || !queryHash.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("queryHash 必须是 64 位 SHA-256 hex");
        }
        if (queryLen < 0) {
            throw new IllegalArgumentException("queryLen 不能为负");
        }
        if (answerLen != null && answerLen < 0) {
            throw new IllegalArgumentException("answerLen 不能为负");
        }
        if (stateHint == null) {
            throw new IllegalArgumentException("stateHint 不能为空");
        }
        createdAt = createdAt != null ? createdAt : Instant.now();
    }
}
