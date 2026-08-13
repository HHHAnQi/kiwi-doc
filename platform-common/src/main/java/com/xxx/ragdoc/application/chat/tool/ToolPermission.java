package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Tool 执行所需的权限类别。每个 Tool 在 {@link ToolDescriptor#requiredPermission()} 声明,
 * ToolExecutor 执行前据此 + {@link ToolExecutionContext#permissionScope()} 校验。
 *
 * <p>设计: 用粗粒度级别 (语义可扩展), 不直接绑定 tenantId / docId。ACL 实参落点在 {@link PermissionScope} (tenantId + 允许文档集
 * + 是否 admin)。
 */
public enum ToolPermission {
    /** 读检索: semantic_search / keyword_search / metadata_search 都用此级别。 */
    READ_RETRIEVE,
    /** 读取指定 Chunk / 文档明文: document_fetch 用; 必须显式 READ 权限 (不仅是可读文档集)。 */
    READ_DOCUMENT,
    /** 引用核验: citation_verify 用; 只读 + 不能扩大检索范围。 */
    VERIFY_CITATION
}
