package com.xxx.ragdoc.domain.shared;

/**
 * trace_id 语义值对象。仅表示"一次 chat 或请求的可追溯标识",
 * 不承担传输职责(传输由 Filter/MDC 完成)。
 */
public record TraceId(String value) {
    public TraceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trace_id 不能为空");
        }
    }
}
