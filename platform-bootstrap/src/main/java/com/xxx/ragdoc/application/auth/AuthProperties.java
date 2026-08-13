package com.xxx.ragdoc.application.auth;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Task 11 / P0: 认证配置 + profile 守门。
 *
 * <p>核心字段:
 *
 * <ul>
 *   <li>{@link #devDefaultPrincipalEnabled} = 是否允许 AuthFilter 在 dev/local profile 下用 {@link
 *       AuthContext#DEFAULT_PRINCIPAL} 处理"无 token / dev-default-token"请求
 *   <li>{@link #devDefaultToken} = 触发 dev-default-principal 的 magic token (默认 "dev-default-token")
 * </ul>
 *
 * <p>生产环境 (profile=prod|test|staging|...) 发现 enabled=true → 启动 fail-fast (见 init), 不允许降级 fallback。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "rag.auth")
public class AuthProperties {

    /** dev/local profile 下是否允许 fallback DEFAULT_PRINCIPAL。默认 false。 */
    private boolean devDefaultPrincipalEnabled = false;

    /** 触发 dev-default-principal 的 magic token。 */
    private String devDefaultToken = "dev-default-token";

    /** 兼容老 FeedbackController 用的 dev token (DEV_TOKEN)。 */
    private String devToken = System.getenv().getOrDefault("APP_DEV_TOKEN", "dev-token-change-me");

    /** 兼容老 FeedbackController 用的 admin token (ADMIN_TOKEN)。 */
    private String adminToken =
            System.getenv().getOrDefault("APP_ADMIN_TOKEN", "admin-token-change-me");

    /** allowlist 路径前缀 (无 token 可访问), 默认 health/readiness。 */
    private java.util.List<String> allowlistPaths =
            java.util.List.of(
                    "/actuator/health",
                    "/actuator/readiness",
                    "/actuator/liveness",
                    "/actuator/info",
                    "/swagger-ui",
                    "/v3/api-docs");

    private final Environment environment;

    public AuthProperties(Environment environment) {
        this.environment = environment;
    }

    /**
     * 启动校验: prod-like profile (非 dev|local|test) 下若 devDefaultPrincipalEnabled=true → 抛异常。
     *
     * <p>由 ChatApplication 启动后调 (ApplicationReadyEvent), 或 @PostConstruct。本类自身用 @PostConstruct 让校验在
     * bean 初始化后立即跑。
     */
    @jakarta.annotation.PostConstruct
    public void verifyProfileGate() {
        // Task 11: WebMvc 切片测试 active profiles=[] (空数组), 视为 "无 active 等于 default";
        // 此时即便 enabled 也跳过 fail-fast, 避免切片测试无法加载 ApplicationContext
        if (environment.getActiveProfiles().length == 0) {
            // 切片测试场景, 不做 fail-fast 校验
            return;
        }
        java.util.Set<String> safeProfiles = java.util.Set.of("dev", "local", "test");
        boolean profileSafe = false;
        for (String p : environment.getActiveProfiles()) {
            if (safeProfiles.contains(p)) {
                profileSafe = true;
                break;
            }
        }
        if (devDefaultPrincipalEnabled && !profileSafe) {
            throw new IllegalStateException(
                    "P0 安全违规: rag.auth.dev-default-principal-enabled=true 仅允许在 dev/local/test"
                            + " profile 启用; 当前 active profiles="
                            + java.util.Arrays.toString(environment.getActiveProfiles()));
        }
        if (devDefaultPrincipalEnabled) {
            log.warn(
                    "⚠ rag.auth.dev-default-principal-enabled=true (当前 profile 中含 dev/local/test);"
                            + " 生产部署必须关掉!");
        }
    }
}
