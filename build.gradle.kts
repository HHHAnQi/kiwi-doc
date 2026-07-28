import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("org.springframework.boot") version "3.3.2" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.diffplug.spotless") version "6.25.0"
}

allprojects {
    group = "com.xxx.ragdoc"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.spring.io/milestone")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    configure<JavaPluginExtension> {
        // V1 暂用 JDK 17 降低上手门槛; V2 接入真实 LLM 长连接后切 21 启用虚拟线程
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    }

    // Lombok 版本集中管理; 子模块只需声明 compileOnly / annotationProcessor
    val lombokVersion = "1.18.34"
    dependencies {
        "compileOnly"("org.projectlombok:lombok:$lombokVersion")
        "annotationProcessor"("org.projectlombok:lombok:$lombokVersion")
        "testCompileOnly"("org.projectlombok:lombok:$lombokVersion")
        "testAnnotationProcessor"("org.projectlombok:lombok:$lombokVersion")
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            // 用 Google Java Format 仅调代码格式,不强加 import 顺序规则以防 IDE 冲突
            googleJavaFormat("1.22.0").aosp()
            removeUnusedImports()
            formatAnnotations()
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
