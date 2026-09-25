// ─────────────────────────────────────────────────────────────────────────────
// 采纳状态：**零生产引用**（G332 实测；同上那条门禁盯着）。
//
// `NetworkResult` 与 app 里在用的 `kotlin.Result` 是**并行**的两套结果类型。
// 采纳意味着把 repository 的返回类型整体换掉（当前是 `Result<T>`），
// 或先建立两者之间的转换约定；两种都要动几十个仓库的签名，属独立一轮。
// 在那之前，本模块的存在只是「设计已定、尚未接线」，不是「已经用上了」。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("org.jetbrains.kotlin.jvm")
}


kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":core:model"))

    testImplementation("junit:junit:4.13.2")
}
