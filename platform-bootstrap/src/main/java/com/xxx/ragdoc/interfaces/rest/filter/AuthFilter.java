package com.xxx.ragdoc.interfaces.rest.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.AuthProperties;
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
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Task 11 / P0: 入站鉴权过滤器 — Deny-by-Default。
 *
 * <h2>修复 (问题 1 根因)</h2>
 *
 * <ul>
 *   <li>无 token / 异常 token / 未知 token / DB 异常 → <b>401</b>; 不再 fallback DEFAULT_PRINCIPAL
 *   <li>仅 dev/local profile + {@code rag.auth.dev-default-principal-enabled=true} 时, magic token
 *       ({@code dev-default-token}) 才走 DEFAULT_PRINCIPAL
 *   <li>认证失败时 <b>不</b> 调 Controller (chain.doFilter 不被执行)
 *   <li>finally 清 AuthContext 防 ThreadLocal 串号
 *   <li>health check / swagger 等 allowlist 路径放行 (无 token 可访问)
 * </ul>
 *
 * <p>401 返回结构: 沿用项目既有 JSON 错误格式 {@code {code, message, trace_id}}。
 */
@Slf4j
@Component("authFilter")
@ConditionalOnProperty(
        prefix = "rag.auth",
        name = "filter-enabled",
        havingValue = "true",
        matchIfMissing = true) // 默认装载; WebMvcTest 用 filter-enabled=false 关掉
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 排在 TraceIdFilter 之后
@Tag(name = "auth")
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 用 {@link ObjectProvider} 可选注入 — 切片测试 (@WebMvcTest 不装 JPA Repositories) 也能装配; 生产环境 JPA 完整加载,
     * repo 始终存在。
     */
    private final ObjectProvider<PrincipalJpaRepository> repoProvider;

    private final AuthProperties authProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthFilter(
            ObjectProvider<PrincipalJpaRepository> repoProvider, AuthProperties authProperties) {
        this.repoProvider = repoProvider;
        this.authProperties = authProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // 1) allowlist 路径直接放行 (健康检查等)
            if (isAllowlisted(request)) {
                // 不 set AuthContext — allowlist 路径不应触碰 AuthContext.currentPrincipal
                chain.doFilter(request, response);
                return;
            }

            // 2) 提取 Bearer token
            String token;
            try {
                token = extractBearerToken(request);
            } catch (IllegalArgumentException formatEx) {
                abort401(response, "malformed_authorization_header: " + formatEx.getMessage());
                return;
            }
            if (token == null) {
                abort401(response, "missing_bearer_token");
                return;
            }

            // 3) dev-default-token 短路 (仅 dev/local profile + flag 开)
            if (authProperties.isDevDefaultPrincipalEnabled()
                    && authProperties.getDevDefaultToken().equals(token)) {
                AuthContext.set(AuthContext.DEFAULT_PRINCIPAL);
                log.debug("auth.dev_default_principal_used");
                chain.doFilter(request, response);
                return;
            }

            // 4) DB 解析 token; 未知 token / DB 异常 → 401 (不 fallback)
            Principal p;
            try {
                p = resolveFromDb(token);
            } catch (RuntimeException dbEx) {
                log.warn(
                        "auth.db_error token={}..., error={}",
                        token.substring(0, Math.min(8, token.length())),
                        dbEx.getMessage());
                // DB 异常返 500 让 SRE 看到, 不静默 fallback
                abort(response, HttpStatus.INTERNAL_SERVER_ERROR, "auth_storage_unavailable");
                return;
            }
            if (p == null) {
                log.warn(
                        "auth.token_unknown token={}...",
                        token.substring(0, Math.min(8, token.length())));
                abort401(response, "invalid_or_expired_token");
                return;
            }

            // 5) 成功 — set ThreadLocal, 调下游
            AuthContext.set(p);
            log.debug(
                    "auth.resolved user={} tenant={} roles={}",
                    p.userId(),
                    p.tenantId(),
                    p.roles());
            chain.doFilter(request, response);
        } finally {
            // 防 ThreadLocal 串号 (P0 不变量: tomcat 线程复用前必须清)
            AuthContext.clear();
        }
    }

    /** allowlist: 任意路径以 {@code /actuator/health} 开头等都放行 (instance/getter 控制)。 */
    private boolean isAllowlisted(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : authProperties.getAllowlistPaths()) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 解析 Authorization: Bearer xxx 头, 返回裸 token; 缺失/非 Bearer → null, 格式错 → 抛 IAE。 */
    private static String extractBearerToken(HttpServletRequest request)
            throws IllegalArgumentException {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header)) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new IllegalArgumentException("authorization header 必须以 'Bearer ' 开头");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("bearer token 不能为空");
        }
        return token;
    }

    /** token → Principal; repo 缺失 (切片测试) 或查不到 → null (caller 决定是 401 还是 fallback)。 */
    private Principal resolveFromDb(String token) {
        PrincipalJpaRepository repo = repoProvider.getIfAvailable();
        if (repo == null) {
            log.warn("auth.principal_repo_unavailable (slice test?)");
            return null;
        }
        return repo.findByToken(token).map(AuthFilter::toDomain).orElse(null);
    }

    private static Principal toDomain(PrincipalEntity e) {
        Set<String> roles = parseRoles(e.getRoles());
        return new Principal(e.getTenantId(), e.getUserId(), roles, e.getToken());
    }

    private static Set<String> parseRoles(String csv) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("role:default");
        if (StringUtils.hasText(csv)) {
            for (String r : csv.split(",")) {
                String trimmed = r.trim();
                if (!trimmed.isEmpty()) roles.add(trimmed);
            }
        }
        return roles;
    }

    // ─── 401 response helpers ───────────────────────────────
    private void abort401(HttpServletResponse response, String reason) throws IOException {
        abort(response, HttpStatus.UNAUTHORIZED, reason);
    }

    private void abort(HttpServletResponse response, HttpStatus status, String reason)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // GlobalExceptionHandler 同款 schema: {code, message, trace_id}
        Map<String, Object> body =
                Map.of(
                        "code", status.value(),
                        "error", status.getReasonPhrase(),
                        "message", reason);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
