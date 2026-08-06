package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5: 单调用上下文 (不可变)。从更高层 ComponentInvocation 派生, 不直接持有完整 Principal。
 *
 * <p>持久化 Fixture 时只用 {@link #tenantId}/{@link #permissionScopeVersion}/{@link #indexVersion} 作 replay key
 * 的一部分, 不写 Principal / Token / userId (即使 userId 进去也只是脱敏 hash)。
 */
public record InvocationContext(
        String requestId,
        String tenantId,
        String permissionScopeVersion,
        String indexVersion,
        String traceId,
        String userIdHash) {

    public InvocationContext {
        if (requestId == null) requestId = "";
        if (tenantId == null) tenantId = "";
        if (permissionScopeVersion == null) permissionScopeVersion = "";
        if (indexVersion == null) indexVersion = "";
        if (traceId == null) traceId = "";
        // userIdHash 是 PR-5 引入的脱敏 (sha256(userId)[:12]); 不写 userId 原值
        if (userIdHash == null) userIdHash = "";
    }
}
