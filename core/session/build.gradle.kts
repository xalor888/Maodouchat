// ─────────────────────────────────────────────────────────────────────────────
// 采纳状态：**已采纳第二块**——`SessionContext` / `SessionContextProvider` /
// `MutableSessionContextProvider` 已从 app 侧 `com.maodouchat.session` 迁入本模块，:app 生产代码直接引用。
//
// 其余会话实现仍在 app 侧（`TokenManager`、刚加的 `CurrentSession`）+
// `ApiService` 的刷新逻辑里；本模块的 `TokenRefreshSingleFlight` /
// `SessionCoordinator` 是那次重构的**目标形状**。
//
// 采纳顺序（建议）：先把 `TokenRefreshSingleFlight` 用到 `ApiService` 的令牌刷新上
// ——那里正是「并发刷新要合流」的现场，行为可对照现有测试验证；
// `SessionCoordinator` 要等 DI 装配（B02）。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("org.jetbrains.kotlin.jvm")
}


kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
