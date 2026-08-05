import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":platform-common"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JPA + Flyway + MySQL
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // MinIO
    implementation("io.minio:minio:8.5.12")

    // HTML 转义工具(feedback 防 XSS)
    implementation("org.apache.commons:commons-text:1.12.0")

    // Milvus SDK
    implementation("io.milvus:milvus-sdk-java:2.5.15")

    // Apache Tika (V2: 真实 PDF/Markdown 解析, 替换 StubParsingTrigger)
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")

    // RocketMQ (V3-W1 parser-service 拆分, ADR-0009 D1 选 RocketMQ; async ParsingTrigger 用)
    implementation("org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.3")

    // WebClient (V2: 调 BGE-M3 / LLM 服务的 OpenAI 兼容协议)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // 可观测埋点
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Phase 1.B + Phase 3.A 共享 (2026-08-03):
    // Resilience4j — CircuitBreaker + Retry + TimeLimiter。
    // Phase 1.B 先在 LlmRouter 上启 CircuitBreaker; Phase 3.A 扩到 BGE/Rerank/Milvus。
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")  // Resilience4j @CircuitBreaker AOP 支持

    // Phase 1 / C2 (2026-08-04, ADR-0011 §3): RedisConversationStore 用。
    // 默认 optional (rag.conversation.enabled=false 时 NoOp 接管, 不连 Redis)。
    // 启用 conversation 后, 由 spring.data.redis.* 配置接入。
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // reactor-test: SSE 流式单终态/取消测试 (PR-0 引入)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:mysql:1.20.1")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}

tasks.withType<BootJar> {
    archiveFileName.set("ragdoc.jar")
    mainClass.set("com.xxx.ragdoc.RagDocApplication")
}

// 启用 Spring Boot 分层 jar,优化镜像
tasks.withType<BootJar> {
    layered {
        isEnabled = true
    }
}
