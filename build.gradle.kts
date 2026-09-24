plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}

import org.gradle.api.artifacts.ProjectDependency

// A01 架构静态检查：模块依赖必须单向无环（core <- domain <- feature <- app）。
// 任何反向/越层依赖在 CI 直接失败。对应清单 A01「为禁止依赖建立静态检查」。
tasks.register("checkArchitecture") {
    group = "verification"
    description = "Enforce one-way module dependency rules (core <- domain <- app)"

    fun layer(path: String): String = when {
        path == ":app" -> "app"
        path.startsWith(":feature") -> "feature"
        path.startsWith(":domain") -> "domain"
        path.startsWith(":core") -> "core"
        else -> "other"
    }

    val allowed = mapOf(
        "core" to setOf("core"),
        "domain" to setOf("core"),
        "feature" to setOf("core", "domain"),
        "app" to setOf("core", "domain", "feature"),
        "other" to emptySet(),
    )

    doLast {
        val violations = mutableListOf<String>()
        rootProject.allprojects.filter { it != rootProject }.forEach { project ->
            val projectLayer = layer(project.path)
            val allowedLayers = allowed[projectLayer] ?: emptySet()
            val declaredProjectDeps = listOf("implementation", "api")
                .mapNotNull { project.configurations.findByName(it) }
                .flatMap { it.dependencies.filterIsInstance<ProjectDependency>() }
                .map { it.dependencyProject.path }
                .distinct()
            declaredProjectDeps.forEach { depPath ->
                val depLayer = layer(depPath)
                if (depLayer !in allowedLayers) {
                    violations += "${project.path} -> $depPath (违规: $projectLayer 不能依赖 $depLayer)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("架构违规 ${violations.size} 处:\n" + violations.joinToString("\n"))
        }
        println(
            "架构检查通过：模块依赖单向无环（core <- domain <- app；feature 层规则保留，" +
                "但当前没有 feature 模块——空壳已在 G328c 删除）。"
        )
    }
}
