package com.xxx.ragdoc.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 架构纪律自动化测试。
 *
 * <p>见 docs/engineering/testing.md §3 与 docs/architecture/domain-model.md §6。 以下规则失败 = CI 红, 保证 DDD
 * 分层不被心脏破坏。
 */
@AnalyzeClasses(packages = "com.xxx.ragdoc")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain层不依赖Spring =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "jakarta.servlet..",
                            "org.hibernate..");

    @ArchTest
    static final ArchRule domain层不依赖Infrastructure =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule interfaces不直接访问Infrastructure =
            noClasses()
                    .that()
                    .resideInAPackage("..interfaces..")
                    .and()
                    .resideOutsideOfPackage("..interfaces.rest.filter..") // V9: AuthFilter 是 web adapter, 解析 Bearer token → domain.Principal 合法引用 infra PrincipalRepository/Entity
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule application层不直接访问Infrastructure =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule 分层依赖单向 =
            slices().matching("com.xxx.ragdoc.(*)..").should().beFreeOfCycles();
}
