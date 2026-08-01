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

    // WebClient (V2: 调 BGE-M3 / LLM 服务的 OpenAI 兼容协议)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // 可观测埋点
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // Testcontainers
    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
