package com.xxx.ragdoc.application.auth;

import com.xxx.ragdoc.domain.auth.Principal;
import java.util.Set;

/**
 * 请求作用域 ThreadLocal principal holder (V9 RAG-Perm-001)。
 *
 * <p>由 {@code interfaces.rest.filter.AuthFilter} 在每个请求开始时 set, 结束时 clear。
 * 不在 {@link com.xxx.ragdoc.application.chat.RetrieveService} 直接拿是因为 RetrieveService
 * 不感知 HTTP — 它只读 {@link #currentPrincipal()}。
 *
 * <p>语义约定: 永远非空。AuthFilter 负责在缺 token 时 set 默认 principal (单租户兼容), 让本类
 * 调用方不用判 null。
 */
public final class AuthContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    /** 默认 principal (单租户兼容: 缺 Authorization 头时用)。 */
    public static final Principal DEFAULT_PRINCIPAL =
            new Principal("default", "dev", Set.of("role:default", "role:user"), "");

    private AuthContext() {}

    public static void set(Principal principal) {
        HOLDER.set(principal == null ? DEFAULT_PRINCIPAL : principal);
    }

    public static Principal currentPrincipal() {
        Principal p = HOLDER.get();
        return p == null ? DEFAULT_PRINCIPAL : p;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
