// ─────────────────────────────────────────────────────────────────────────────
// 采纳状态：**零生产引用**（G332 实测；门禁 `ClientArchitectureTest.core modules are
// either adopted by production code or explicitly registered as not yet` 会盯着）。
//
// 里面的两份契约（`Clock`、`DispatcherProvider`）目前只被 `:core:testing` 的契约测试用到。
// 留着而不是删掉：它们是「领域层不直接摸 System.currentTimeMillis()/Dispatchers」这条
// 边界的落点，删掉等于把边界和契约测试一起丢。
//
// 采纳要付的代价（写在这里，免得下次当成「改个 import」）：app 里有**几百处**
// 直接调用 `System.currentTimeMillis()` / `Dispatchers.IO`，采纳意味着把这些点改成
// 构造器注入的时钟与调度器——一次跨全模块的参数化改造，且必须先有 DI 装配（B02）。
// 在 DI 到位之前单点采纳只会造出「两种取时间的方式」。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
