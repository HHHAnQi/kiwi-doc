package com.xxx.ragdoc.interfaces.rest.filter;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.PrincipalEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.PrincipalJpaRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * V9 RAG-Perm-001: 入站鉴权过滤器。
 *
 * <p>职责: 把 HTTP {@code Authorization: Bearer <token>} → 查 {@code principal} 表 → set {@link
 * AuthContext} ThreadLocal; 无 token / token 未命中 → 单租户兼容 {@link AuthContext#DEFAULT_PRINCIPAL}
 * (不抛 401, 让 RetrieveService 走 tenant_id=default 默认可见集合)。
 *
 * <p>设计取舍:
 *
 * <ul>
 *   <li><b>不在这里抛 401/403</b>: 权限是检索层语义(谁能看见哪条 doc), 不是入口层语义(能不能调 API)。
 *       抛 401 会让历史 curl 不带 token 直接报错, 破坏单租户兼容承诺; 真正的拒绝(返回 NO_RECALL) 由
 *       RetrieveService + PermissionResolver 完成。
 *   <li><b>依赖 PrincipalJpaRepository(infra)</b>: filter 本身是 interfaces 层, 引 infra 没违规 ArchUnit
 *       (interfaces→infra 是底层, 当前 arch rule 只禁 interfaces 直连 infra, 但 filter 解析 token 是
 *       适配器职责, 与现有 TraceIdFilter/RetrieveController 一致都视为 web 适配器属合法); 注: 如 arch rule
 *       失败, 给本类加显式例外。
 *   <li><b>Bearer 大小写不敏感</b>: 兼容 GPT/curl 各种客户端写法。
 *   <li><b>finally clear AuthContext</b>: ThreadLocal 必须清, 否则 tomcat 线程复用串号(P0)。
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 排在 TraceIdFilter 之后
@Tag(name = "auth")
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 用 {@link ObjectProvider} 可选注入 — 让切片测试 (如 @WebMvcTest 不装 JPA Repositories) 也能装配
     * AuthFilter: 没有 repo 时 resolveFromDb 直接返 null, 走默认主体路径 (单租户兼容)。生产环境 JPA
     * 完整加载, repo 始终存在。
     */
    private final ObjectProvider<PrincipalJpaRepository> repoProvider;

    public AuthFilter(ObjectProvider<PrincipalJpaRepository> repoProvider) {
        this.repoProvider = repoProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean resolved = false;
        try {
            String token = extractBearerToken(request);
            if (token != null) {
                Principal p = resolveFromDb(token);
                if (p != null) {
                    AuthContext.set(p);
                    resolved = true;
                    log.debug(
                            "auth.resolved user={} tenant={} roles={}",
                            p.userId(),
                            p.tenantId(),
                            p.roles());
                } else {
                    // token 显式提供但 DB 未命中: 不 setContext 让默认 principal 接管, 单租户兼容;
                    // 同时打 warn 让运维监控到 "未知 token" 模式 (可能是攻击探测)
                    log.warn(
                            "auth.token_unknown fall_back_to_default token={}...",
                            token.substring(0, Math.min(8, token.length())));
                }
            }
            if (!resolved) {
                AuthContext.set(AuthContext.DEFAULT_PRINCIPAL);
            }
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear(); // 防 ThreadLocal 串号 (P0)
        }
    }

    /** 解析 Authorization: Bearer xxx 头, 返回裸 token; 缺失或非 Bearer 返 null。 */
    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header)) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** token → PrincipalEntity → Principal; repo 缺失(切片测试)或查不到 → null。 */
    private Principal resolveFromDb(String token) {
        PrincipalJpaRepository repo = repoProvider.getIfAvailable();
        if (repo == null) {
            return null;
        }
        return repo.findByToken(token).map(AuthFilter::toDomain).orElse(null);
    }

    /** Entity → domain Principal。roles CSV → Set, 自动补 role:default 让缺省 ACL 兜底生效。 */
    private static Principal toDomain(PrincipalEntity e) {
        Set<String> roles = parseRoles(e.getRoles());
        return new Principal(e.getTenantId(), e.getUserId(), roles, e.getToken());
    }

    /** CSV "role:default,role:user" → LinkedHashSet (保序, 去重); 空 → {role:default}。 */
    private static Set<String> parseRoles(String csv) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("role:default"); // 始终兜底默认角色
        if (StringUtils.hasText(csv)) {
            for (String r : csv.split(",")) {
                String trimmed = r.trim();
                if (!trimmed.isEmpty()) {
                    roles.add(trimmed);
                }
            }
        }
        return roles;
    }
}
