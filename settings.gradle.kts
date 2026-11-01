pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Maodouchat"

// 模块清单 = **真实架构**，不是目标架构的占位。
//
// G328c：此前这里列了 17 个模块，其中 8 个**零主源码**（feature:chat/contacts/explore/
// settings、core:database、domain:conversation/groups/calls）——只有一份 build.gradle.kts，
// 没有任何模块依赖它们，却让清单看起来像「已经拆成多模块了」。审计把这条列为
// 「声明了却没用的架构」。空壳已删除；要继续走多模块，就从**真的搬一个 feature 出去**开始，
// 而不是先占位。
//
// 留下的 core/util、core/serialization、core/network 有真实内容
// （clock/id/dispatcher、MaodouJson、NetworkResult），
// 但目前只被 :core:testing 的 testImplementation 引用——即「已冻结的共享契约，尚未被生产代码采纳」。
// core/session 自本轮起被生产代码采纳：SessionContext / SessionContextProvider / MutableSessionContextProvider 已迁入，:app 直接依赖。
// 要判断某个模块是不是真在用，跑 `grep -rn "com.maodouchat.core.<模块>" app/src core domain server/src`。
include(":app")
include(
    ":core:model",
    ":core:util",
    ":core:serialization",
    ":core:network",
    ":core:realtime",
    ":core:crypto",
    ":core:session",
    ":core:testing",
    ":domain:messaging",
)
