package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Instant;

/**
 * PR-4 / EMS-PR4: 单次 Tool 调用的不可变执行上下文。每个 Tool execute() 都通过本 context 获取身份 / 权限范围 /
 * 截止时间, <b>不允许</b> 从 input 读 tenantId / userId / roles。
 *
 * <h2>关键不变量</h2>
 *
 * <ul>
 *   <li>{@link #principal()} 来自已鉴权 AuthContext; tenantId / userId / roles 只读
 *   <li>{@link #permissionScope()} 在调用前后都生效:
 *       <ul>
 *         <li>Tool 执行前用 scope.allowedDocumentIds 过滤可读文档集
 *         <li>Tool 执行后用 scope.tenantId 对每条返回 Evidence 复核 (双重保险)
 *       </ul>
 *   <li>{@link #deadline()} Instant epoch millis; Tool 在阻塞调用前自检; 不依赖长 DB 事务 timeout
 *   <li>{@link #runId()} / {@link #requestId()} 用于 trace + dedup key;
 *       并发请求不应串线 (Executor 在调用前注入新的 context 实例)
 *   <li>{@link #indexVersion()} = Milvus 索引版本 / embedding 模型版本。审计结论: 项目目前无显式 version 字段
 *       (检索 props 有 model 名), PR-4 用 {@code embeddingModel|indexProps} 拼接作为稳定性 key。
 * </ul>
 */
public record ToolExecutionContext(
        String requestId,
        String runId,
        Principal principal,
        String tenantId,
        PermissionScope permissionScope,
        String indexVersion,
        Instant deadline) {

    public ToolExecutionContext {
        if (principal == null) {
            throw new IllegalArgumentException("ToolExecutionContext.principal 必须来自 AuthContext");
        }
        if (tenantId == null || !tenantId.equals(principal.tenantId())) {
            // tenantId 只能从 Principal 派生; 客户端不能传 tenantId 改变 scope
            throw new IllegalArgumentException(
                    "ToolExecutionContext.tenantId 必须与 principal.tenantId() 一致: 设置=" + tenantId
                            + " principal=" + principal.tenantId());
        }
        if (permissionScope == null) {
            throw new IllegalArgumentException("ToolExecutionContext.permissionScope 必填");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("ToolExecutionContext.runId 必填 (调用方生成 request-scoped)");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("ToolExecutionContext.requestId 必填");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("ToolExecutionContext.deadline 必填");
        }
        if (indexVersion == null || indexVersion.isBlank()) {
            // 缺省值, 让 Audit 数据可读
            indexVersion = "unknown";
        }
    }

    /** 检查是否已触达 deadline; Tool 在长-blocking 调用前自检。 */
    public boolean isExpired() {
        return Instant.now().isAfter(deadline);
    }

    /** 剩余毫秒; 给阻塞调用的 timeout 参数用。永远不会负数 (0 = 已到 deadline)。 */
    public long remainingMillis() {
        long r = deadline.toEpochMilli() - System.currentTimeMillis();
        return r < 0 ? 0 : r;
    }
}
