package com.xxx.ragdoc.common.exception;

/**
 * 错误码体系,统一用枚举集中管理。 命名: {DOMAIN}_{TYPE},全大写下划线。 与 {@code GlobalExceptionHandler} 配合映射 HTTP 状态码。
 *
 * @see ErrorCode 系统对外暴露的稳定契约,禁止随版本随意改名。
 */
public enum ErrorCode {
    // 文档域 ====================================================================
    DOC_NOT_FOUND(404, "DOC_NOT_FOUND", "文档不存在"),
    DOC_INVALID_TYPE(400, "DOC_INVALID_TYPE", "不支持的文件类型"),
    DOC_TOO_LARGE(413, "DOC_TOO_LARGE", "文件过大"),
    DOC_NOT_READY(409, "DOC_NOT_READY", "文档未就绪"),
    DOC_PARSE_FAILED(422, "DOC_PARSE_FAILED", "文档解析失败"),
    DOC_HASH_EXISTS(409, "DOC_HASH_EXISTS", "文件已存在"),
    DOC_NOT_FAILED(409, "DOC_NOT_FAILED", "仅失败的文档可重试"),

    // Chunk 域 ==================================================================
    CHUNK_NOT_FOUND(404, "CHUNK_NOT_FOUND", "chunk 不存在"),

    // 检索/问答域 ================================================================
    // 注意: 业务降级场景(召回为空 / LLM 不可用)不走异常路径,
    // 改走 ChatResponse.stateHint(StateHint 枚举)。
    // 详见 docs/architecture/error-model.md §业务降级协议 + domain/shared/StateHint.java
    RAG_LLM_TIMEOUT(504, "RAG_LLM_TIMEOUT", "LLM 推理超时"),
    RAG_EMBEDDING_FAILED(500, "RAG_EMBEDDING_FAILED", "向量化失败"),
    // PR-2: 客户端显式选择 AGENTIC 模式, 但 Agentic Pipeline 未实现 → 不静默回退 Classic
    AGENTIC_MODE_UNAVAILABLE(422, "AGENTIC_MODE_UNAVAILABLE", "Agent 模式暂未启用"),
    // PR-2: Orchestrator 找不到对应 pipeline type 的 bean → 失败关闭 (HTTP 500)
    PIPELINE_NOT_FOUND(500, "PIPELINE_NOT_FOUND", "请求的 pipeline 未注册"),
    // PR-4: Tool Registry / 执行相关
    TOOL_NOT_FOUND(404, "TOOL_NOT_FOUND", "请求的 tool 未注册"),
    TOOL_INVALID_ARGUMENT(400, "TOOL_INVALID_ARGUMENT", "Tool 输入非法"),
    TOOL_PERMISSION_DENIED(403, "TOOL_PERMISSION_DENIED", "Tool 调用权限不足"),
    TOOL_EXECUTION_FAILED(500, "TOOL_EXECUTION_FAILED", "Tool 执行失败"),
    TOOL_TIMEOUT(504, "TOOL_TIMEOUT", "Tool 调用超时"),
    TOOL_DEPENDENCY_UNAVAILABLE(503, "TOOL_DEPENDENCY_UNAVAILABLE", "Tool 依赖暂不可用"),

    // 反馈域 ====================================================================
    TRACE_NOT_FOUND(404, "TRACE_NOT_FOUND", "trace_id 不存在"),
    FEEDBACK_EXISTS(409, "FEEDBACK_EXISTS", "该 trace_id 已有反馈"),

    // 系统域 ====================================================================
    UNAUTHORIZED(401, "UNAUTHORIZED", "未授权"),
    FORBIDDEN(403, "FORBIDDEN", "禁止访问"),
    SYS_INVALID_ARGUMENT(400, "SYS_INVALID_ARGUMENT", "参数校验失败"),
    SYS_INTERNAL(500, "SYS_INTERNAL", "系统内部错误");

    private final int httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
