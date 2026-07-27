package com.xxx.ragdoc.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc / OpenAPI 配置。启动后访问 /swagger-ui.html 即可。
 * 代码生成的 openapi.yaml 由 springdoc 自动维护,见 docs/features/api-contracts.md。
 */
@Configuration
public class OpenApiConfig {

    private static final String JWT_BEARER = "bearer-jwt";

    @Bean
    public OpenAPI ragDocOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RAG Doc Platform API")
                        .description("企业私有多模态 RAG 智能中台 - V1")
                        .version("0.1.0"))
                .addSecurityItem(new SecurityRequirement().addList(JWT_BEARER))
                .schemaRequirement(JWT_BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
