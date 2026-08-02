import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// parser-service 是 V3 第 1 周拆出的独立异步解析服务(ADR-0005 / ADR-0009)。
// 入口: RocketMQ consumer(无 HTTP 入口); 消费 chat-app 发的 parse-task-submit。
//
// 依赖关系:
//   platform-common  共享异常 / DTO
//   domain layer     不再复用 chat-app 的 domain.Document(表归属 + state 机一致即可),
//                     由 parser-service 本地维护 ParseTask domain class
//
// Spring Boot 3.3.2 与 platform-bootstrap 对齐。
dependencies {
    implementation(project(":platform-common"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // RocketMQ client — 与 ADR-0009 D1 决策一致
    implementation("org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.3")

    // Flyway 不在 parser-service — 表归属 chat-app(chat-app 启动时建表)
    runtimeOnly("com.mysql:mysql-connector-j")

    // MinIO(下载原始文件)
    implementation("io.minio:minio:8.5.12")

    // Milvus SDK(写 chunks 索引)
    implementation("io.milvus:milvus-sdk-java:2.5.15")

    // Apache Tika(从 chat-app 迁入)
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")

    // WebClient(调 BGE-M3 embedding)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.withType<BootJar> {
    // 独立可执行 jar, 启动脚本 docker/k8s 用
    mainClass.set("com.xxx.ragdoc.parser.ParserServiceApplication")
    archiveBaseName.set("parser-service")
}
