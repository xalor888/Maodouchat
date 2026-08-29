package com.maodouchat.core.testing

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * A01 类级架构静态检查（配合根 build.gradle.kts 的 checkArchitecture 模块级检查）。
 *
 * 约束：core / domain 是纯 Kotlin 领域层，不得依赖 Android 框架，也不得读取
 * `MaodouchatApp` / `ApiService` / DAO 等应用层全局单例（对应「领域类不得读取
 * MaodouchatApp.instance」）。随着代码迁入这些模块，此检查会开始真实生效。
 */
@AnalyzeClasses(
    packages = ["com.maodouchat.core", "com.maodouchat.domain"],
)
class ArchitectureTest {

    @ArchTest
    val coreAndDomainMustNotDependOnAndroid: ArchRule =
        noClasses()
            .that().resideInAnyPackage("..core..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("android..", "androidx..", "com.android..")

    @ArchTest
    val coreAndDomainMustNotAccessAppSingletons: ArchRule =
        noClasses()
            .that().resideInAnyPackage("..core..", "..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.maodouchat.network..", "com.maodouchat.data..", "com.maodouchat.ui..")
}
