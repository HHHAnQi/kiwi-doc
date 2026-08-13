package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.application.chat.tool.ToolStatus;
import lombok.RequiredArgsConstructor;

/**
 * PR-5: Tool 的 {@link ObjectResultMapper}。让 ToolResult 可被 Harness 记录/回放; ToolError 安全映射到
 * FixtureError。
 *
 * <p>REPLAY 时 ToolExecutor 之外层调用方拿到 ToolResult=outcome.success() 的反序列化 copy, 不再触发
 * Milvus/Embedding/Verifier。
 *
 * <p>关键: ToolError 不保存完整堆栈; safeMessage 已经是脱敏的 (PR-4 ToolError 契约)。
 */
@RequiredArgsConstructor
public class ToolHarnessAdapter implements ObjectResultMapper {

    private final ObjectMapper mapper;
    private final CanonicalJson canonical;

    public ToolHarnessAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.canonical = new CanonicalJson(mapper);
    }

    @Override
    public String requestHash(Object request) {
        // request 实现了 ToolInput.normalizedForDedup()
        if (request instanceof com.xxx.ragdoc.application.chat.tool.ToolInput ti) {
            return canonical.hashObject(java.util.Map.of("normalized", ti.normalizedForDedup()));
        }
        return canonical.hashObject(request);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object fromFixtureResponse(JsonNode responseNode, FixtureError error) {
        // FAIL/EMPTY/PERMISSION/TIMEOUT — 重塑失败 ToolResult (没调真实 Tool)
        if (error != null) {
            ToolStatus status = mapToToolStatus(error);
            com.xxx.ragdoc.application.chat.tool.ToolError te =
                    new com.xxx.ragdoc.application.chat.tool.ToolError(
                            error.errorCode(), error.safeMessage(), "", error.retryable());
            // ToolResult.failure 需要 callId/toolName/toolVersion, 我们用 placeholder; caller 用前 reset
            return ToolResult.failure(
                    "replay", "replayed-tool", "v1", status, te, 0, java.util.Map.of());
        }
        // SUCCESS → 反序列化 output (Tool 自己的 typed record); 由 caller 用 resultClass 解析器
        // 为保持通用性, 这里返回一个 wrapper Node, 让 caller 用 mapper.convertValue 转 typed
        return responseNode;
    }

    @Override
    public FixtureOutcome.OutcomeResult toOutcome(Object liveResult, Throwable thrown) {
        if (thrown != null) {
            FixtureError err =
                    new FixtureError(
                            "TOOL_EXCEPTION",
                            safeMsg(thrown.getMessage()),
                            false,
                            thrown.getClass().getSimpleName(),
                            FixtureError.Category.GENERIC);
            return FixtureOutcome.OutcomeResult.error(err);
        }
        if (liveResult instanceof ToolResult<?> tr) {
            ToolStatus s = tr.status();
            FixtureError err =
                    tr.error() == null
                            ? null
                            : new FixtureError(
                                    tr.error().errorCode(),
                                    tr.error().safeMessage(),
                                    tr.error().retryable(),
                                    tr.error().dependency(),
                                    mapToFixtureCategory(s));
            JsonNode respNode =
                    tr.output() == null
                            ? null
                            : canonical.canonicalize(canonical.toJsonNode(tr.output()));
            return new FixtureOutcome.OutcomeResult(mapToOutcome(s), respNode, err);
        }
        return FixtureOutcome.OutcomeResult.success(
                canonical.canonicalize(canonical.toJsonNode(liveResult)));
    }

    private static FixtureOutcome.Outcome mapToOutcome(ToolStatus s) {
        return switch (s) {
            case SUCCESS -> FixtureOutcome.Outcome.SUCCESS;
            case EMPTY_RESULT -> FixtureOutcome.Outcome.EMPTY_RESULT;
            case PERMISSION_DENIED -> FixtureOutcome.Outcome.PERMISSION_DENIED;
            case TIMEOUT -> FixtureOutcome.Outcome.TIMEOUT;
            case CANCELLED -> FixtureOutcome.Outcome.CANCELLED;
            default -> FixtureOutcome.Outcome.ERROR;
        };
    }

    private static ToolStatus mapToToolStatus(FixtureError err) {
        return switch (err.category()) {
            case TIMEOUT -> ToolStatus.TIMEOUT;
            case PERMISSION -> ToolStatus.PERMISSION_DENIED;
            case CANCELLED -> ToolStatus.CANCELLED;
            case EMPTY -> ToolStatus.EMPTY_RESULT;
            case INVALID_ARGUMENT -> ToolStatus.INVALID_ARGUMENT;
            case DEPENDENCY -> ToolStatus.DEPENDENCY_UNAVAILABLE;
            default -> ToolStatus.TERMINAL_ERROR;
        };
    }

    private static FixtureError.Category mapToFixtureCategory(ToolStatus s) {
        return switch (s) {
            case EMPTY_RESULT -> FixtureError.Category.EMPTY;
            case TIMEOUT -> FixtureError.Category.TIMEOUT;
            case PERMISSION_DENIED -> FixtureError.Category.PERMISSION;
            case CANCELLED -> FixtureError.Category.CANCELLED;
            case INVALID_ARGUMENT -> FixtureError.Category.INVALID_ARGUMENT;
            case DEPENDENCY_UNAVAILABLE, RETRYABLE_ERROR -> FixtureError.Category.DEPENDENCY;
            default -> FixtureError.Category.GENERIC;
        };
    }

    private static String safeMsg(String m) {
        if (m == null) return "";
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
