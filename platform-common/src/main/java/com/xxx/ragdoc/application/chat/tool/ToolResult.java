package com.xxx.ragdoc.application.chat.tool;

import java.util.Map;

/**
 * PR-4 / EMS-PR4: Tool 执行结果的不可变结构化返回。
 *
 * <p>执行成功 → status=SUCCESS, output 含 Evidence / Verification / 读取结果。 执行失败 → status=非 SUCCESS, error
 * 携 {@link ToolError}; output=null。
 *
 * <h2>不变量</h2>
 *
 * <ul>
 *   <li>{@link #callId()} 全局唯一 (runId + 调用序号); Trace / Metrics 都用它关联
 *   <li>{@link #toolName()} + {@link #toolVersion()} 与 {@link ToolDescriptor} 一致, 让 Trace 不依赖
 *       className
 *   <li>{@link #status()} 必填; <b>不允许只 SUCCESS/FAILED 二元表达</b>, 用 {@link ToolStatus} 9 个态之一
 *   <li>{@link #latencyMs()} ≥ 0, 由 Executor 度量填充 (不由 Tool 自己填)
 *   <li>{@link #metadata()} 默认空 map, 由 Executor 填装 dedup / permissionScopeVersion / indexVersion 等;
 *       不放 用户敏感原文
 * </ul>
 *
 * @param <T> tool 特定 output 类型, 必须实现 {@link ToolOutput}
 */
public record ToolResult<T extends ToolOutput>(
        String callId,
        String toolName,
        String toolVersion,
        ToolStatus status,
        T output,
        ToolError error,
        long latencyMs,
        boolean retryable,
        Map<String, Object> metadata) {

    public ToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("ToolResult.callId 必填");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("ToolResult.toolName 必填");
        }
        if (toolVersion == null || toolVersion.isBlank()) {
            throw new IllegalArgumentException("ToolResult.toolVersion 必填");
        }
        if (status == null) {
            throw new IllegalArgumentException("ToolResult.status 必填");
        }
        if (status == ToolStatus.SUCCESS && output == null) {
            // SUCCESS 必须有 output; EMPTY_RESULT 等其他态允许 output=null
            throw new IllegalArgumentException(
                    "ToolResult SUCCESS 但 output=null (应为 EMPTY_RESULT?)");
        }
        if (status != ToolStatus.SUCCESS && error == null) {
            // 非 SUCCESS 必须有 error (哪怕只是 EMPTY_RESULT 的简短描述); 不允许 "失败但无原因"
            throw new IllegalArgumentException(
                    "ToolResult 非 SUCCESS 但 error=null (status=" + status + ")");
        }
        if (latencyMs < 0) {
            latencyMs = 0;
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 成功工厂: output 必填, status=SUCCESS, error=null。 */
    public static <T extends ToolOutput> ToolResult<T> success(
            String callId,
            String toolName,
            String toolVersion,
            T output,
            long latencyMs,
            Map<String, Object> metadata) {
        return new ToolResult<>(
                callId,
                toolName,
                toolVersion,
                ToolStatus.SUCCESS,
                output,
                null,
                latencyMs,
                false,
                metadata);
    }

    /** 空结果工厂 (e.g. retrieve 0 hits / chunk 不存在):不算失败但是有意义, 可缓存可去重。 */
    public static <T extends ToolOutput> ToolResult<T> empty(
            String callId,
            String toolName,
            String toolVersion,
            ToolError error,
            long latencyMs,
            Map<String, Object> metadata) {
        return new ToolResult<>(
                callId,
                toolName,
                toolVersion,
                ToolStatus.EMPTY_RESULT,
                null,
                error,
                latencyMs,
                false,
                metadata);
    }

    /** 失败工厂 — 通用, 任意 status (非 SUCCESS); 调用方决定 STATUS。 */
    public static <T extends ToolOutput> ToolResult<T> failure(
            String callId,
            String toolName,
            String toolVersion,
            ToolStatus status,
            ToolError error,
            long latencyMs,
            Map<String, Object> metadata) {
        if (status == ToolStatus.SUCCESS) {
            throw new IllegalArgumentException("failure() 不能用于 SUCCESS, 改用 success()");
        }
        boolean retry = status.retryable() || error != null && error.retryable();
        return new ToolResult<>(
                callId, toolName, toolVersion, status, null, error, latencyMs, retry, metadata);
    }
}
