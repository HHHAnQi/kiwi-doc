plugins {
    java
    id("io.spring.dependency-management")
}

// platform-common 是纯 Java 模块,不引 Spring 上下文,只含异常基类、DTO、工具、领域层与端口定义。
// 让领域层和应用层共享错误体系, 而不污染 domain。
//
// V3 parser-service 拆分后, ChunkingService / MarkdownStructurer / TextCleaner / ChunkingProperties
// 被 chat-app 与 parser-service 共用 → 下沉到本模块(共享层)。
// @Component / @ConfigurationProperties 是 Spring 注解, 这里只在编译期引用(compileOnly),
// 运行时由消费方(platform-bootstrap / parser-service)的 Spring 容器解析。
dependencies {
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    // 仅编译期需要,运行时由 platform-bootstrap / parser-service 提供
    compileOnly("org.springframework:spring-web:6.1.12")
    compileOnly("org.springframework:spring-context:6.1.12")
    compileOnly("org.springframework.boot:spring-boot:3.3.2")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    // PR-3.2: Router 评测测试需要 jackson 解析 router_cases.jsonl (仅测试 classpath)
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
