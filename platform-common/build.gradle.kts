plugins {
    java
    id("io.spring.dependency-management")
}

// platform-common 是纯 Java 模块,不引 Spring 上下文,只含异常基类、DTO、工具。
// 让领域层和应用层共享错误体系,而不污染 domain。

dependencies {
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    // 仅编译期需要,运行时由 platform-bootstrap 提供
    compileOnly("org.springframework:spring-web:6.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
