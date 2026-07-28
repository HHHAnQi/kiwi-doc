package com.xxx.ragdoc.application.auth;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * V1 鉴权配置: 仅 dev/admin token 双令牌。 V4 升级为完整 RBAC + JWT。
 *
 * <p>见 docs/architecture/security.md §鉴权矩阵 + .env.example。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 普通用户 token (V1 硬编码, 写 README) */
    private String devToken;

    /** 管理员 token (查询全局 feedback 列表等用) */
    private String adminToken;

    @PostConstruct
    public void warnIfDefault() {
        if (devToken == null || devToken.isBlank() || devToken.contains("change-me")) {
            log.warn("⚠️ app.auth.dev-token 仍是默认值, 生产环境必须修改 (见 .env.example)");
        }
    }
}
