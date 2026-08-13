package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Tool 元数据。Registry 按 (name, version) 查找; name+version 在启动期 fail-fast 防重复。
 *
 * <h2>字段约束</h2>
 *
 * <ul>
 *   <li>{@link #name()} 必须是 [a-z_]+ (与 LLM Tool 名风格一致); Registry 校验
 *   <li>{@link #version()} semver-like (e.g. "v1"); 用于 dedup / trace
 *   <li>{@link #description()} 含适用 + 不适用场景, 让 LLM Agent 上线后能选合理; PR-4 的 Registry 不会 根据 description
 *       文字执行任何逻辑 (避免 prompt-injection 风险)
 *   <li>{@link #inputSchemaVersion()} / {@link #outputSchemaVersion()}: 描述 input/output record 类名 +
 *       字段 hash, 让 fixture replay 不误命中旧 schema 的结果
 *   <li>{@link #timeout()} 必须由服务端 (Tool 自身) 决定; LLM/客户端不能扩大
 *   <li>{@link #maxResults()} 服务端硬上限 (Tool 内部 trim)
 *   <li>{@link #idempotent()}: read-only 工具全为 true (semantic_search / metadata_search /
 *       document_fetch / citation_verify / keyword_search 全是 read, 自然 idempotent)
 *   <li>{@link #costCategory()} 让 Planner / Sufficiency 估算预算用
 * </ul>
 */
public record ToolDescriptor(
        String name,
        String version,
        String description,
        String inputSchemaVersion,
        String outputSchemaVersion,
        ToolPermission requiredPermission,
        java.time.Duration timeout,
        int maxResults,
        boolean idempotent,
        ToolCostCategory costCategory) {

    public ToolDescriptor {
        if (name == null || !name.matches("^[a-z][a-z0-9_]{1,63}$")) {
            throw new IllegalArgumentException(
                    "ToolDescriptor.name 必须是 [a-z][a-z0-9_]{1,63}, 实际=" + name);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.version 必填");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.description 必填 (含适用 + 不适用场景)");
        }
        if (requiredPermission == null) {
            throw new IllegalArgumentException("ToolDescriptor.requiredPermission 必填");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("ToolDescriptor.timeout 必须为正 Duration");
        }
        if (maxResults <= 0 || maxResults > 100) {
            throw new IllegalArgumentException("ToolDescriptor.maxResults 必须在 (0, 100]");
        }
        if (costCategory == null) {
            costCategory = ToolCostCategory.UNKNOWN;
        }
        if (inputSchemaVersion == null || inputSchemaVersion.isBlank()) {
            inputSchemaVersion = "v1";
        }
        if (outputSchemaVersion == null || outputSchemaVersion.isBlank()) {
            outputSchemaVersion = "v1";
        }
    }
}
