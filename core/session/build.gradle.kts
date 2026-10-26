// ─────────────────────────────────────────────────────────────────────────────
// 采纳状态：**零生产引用**（G332 实测；同上那条门禁盯着）。
//
// app 侧的会话实现目前在 `com.maodouchat.session`（`SessionContext`、`TokenManager`、
// 刚加的 `CurrentSession`）+ `ApiService` 的刷新逻辑里；本模块的
// `TokenRefreshSingleFlight` / `SessionCoordinator` 是那次重构的**目标形状**。
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
