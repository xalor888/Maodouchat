package com.maodouchat.core.testing

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import org.junit.jupiter.api.Test
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * G222b：**覆盖面守卫**。
     *
     * 上面两条 ArchUnit 规则看着很硬，但有一个结构性盲区一直没人说过：
     * 本模块是**纯 JVM**，只能依赖同平台模块。而 `core/crypto`、`core/network`、
     * `core/realtime`、`core/session` 是 **Android library**
     * （platform-type = androidJvm）——往 `core/testing` 加它们的依赖会
     * 「No matching variant」直接编译失败。所以上面的规则对这 4 个模块
     * **结构上照不到**（实测：只补 core/util 之后，ArchUnit 共看见 103 个类 /
     * 4 个包；这 4 个 Android 模块的类一个都不在）。
     *
     * 这条测试把那个事实**钉住**，而不是让它埋在注释里：
     * 1. 下限——若低于 [MIN_SCANNABLE_CLASSES]，说明某个 JVM 模块掉出了 classpath
     *    （有人从 build.gradle.kts 里删了依赖），规则会开始静默失效；
     * 2. 盲区集合必须**正好**是那 4 个 Android 包——哪天它们改成 JVM 模块，
     *    这里会红，提醒你补依赖表（否则白捡的覆盖没人用）。
     *
     * 对 Android 模块的架构约束由 `:app` 侧的 ClientArchitectureTest 补
     * （它扫 app/src/main，而 app 依赖这些模块）。
     */
    @Test
    fun `the A01 gate's coverage envelope is pinned`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.maodouchat.core", "com.maodouchat.domain")
        assertTrue(
            classes.size >= MIN_SCANNABLE_CLASSES,
            "ArchUnit 只看见 ${classes.size} 个类，低于下限 $MIN_SCANNABLE_CLASSES——" +
                "有 JVM 模块掉出了 core/testing 的依赖表，A01 规则正在静默失效。",
        )

        val scannable = classes.map { it.packageName }.toSortedSet()
        val blind = STRUCTURALLY_UNREACHABLE_PACKAGES.filterNot { pkg ->
            scannable.any { it == pkg || it.startsWith("$pkg.") }
        }.toSortedSet()
        assertEquals(
            STRUCTURALLY_UNREACHABLE_PACKAGES.toSortedSet(),
            blind,
            "结构性盲区变了。若某个 Android 模块改成了 JVM 模块，记得把它加进 " +
                "core/testing/build.gradle.kts 的依赖表，否则白捡的覆盖没人用。",
        )
    }

    private companion object {
        /** G222b 实测 103（core/model 5 + core/serialization 1 + core/util 4 + domain/messaging 93）。 */
        const val MIN_SCANNABLE_CLASSES = 100

        /**
         * JVM 测试结构上照不到的包（Android library 模块）。
         * 对它们的约束由 :app 侧 ClientArchitectureTest 负责。
         */
        val STRUCTURALLY_UNREACHABLE_PACKAGES = setOf(
            "com.maodouchat.core.crypto",
            "com.maodouchat.core.network",
            "com.maodouchat.core.realtime",
            "com.maodouchat.core.session",
        )
    }
}
