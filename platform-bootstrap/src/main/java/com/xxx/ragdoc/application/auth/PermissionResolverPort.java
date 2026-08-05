package com.xxx.ragdoc.application.auth;

import com.xxx.ragdoc.domain.auth.Principal;
import java.util.Set;

/**
 * V9 RAG-Perm-001: 把 Principal 解析成"可读 docId 集合"的应用层端口。
 *
 * <p>RetrieveService(application) 依赖此接口, 不直接碰 infra Repository — 这维持 ArchUnit
 * "application 不依赖 infrastructure" 的纪律不变。
 *
 * <p>实现: {@code infrastructure.auth.AclPermissionResolver}, 它可以合法引用 JPA Repository。
 *
 * <p>语义约定 (与实现侧文档一致):
 *
 * <ul>
 *   <li>返回 {@code null} = admin 哨兵, 调用方不加 docId 子句 (仍受 tenant 过滤)
 *   <li>返回非空集合 = 显式白名单
 *   <li>返回空集合 = 无可读文档, 调用方应短路返回 NO_RECALL
 * </ul>
 */
public interface PermissionResolverPort {

    /**
     * 解析 principal 可读的 document id 集合。
     *
     * @param principal 请求级 principal (非空; 由 AuthFilter 写入 AuthContext)
     * @return null = admin 哨兵; 集合 = 可读 docId 白名单 (空集合表示无可读)
     */
    Set<Long> resolveReadableDocIds(Principal principal);
}
