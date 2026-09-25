// ─────────────────────────────────────────────────────────────────────────────
// 采纳状态：**零生产引用**（G332 实测；同上那条门禁盯着）。
//
// ⚠️ 采纳前必须决定一件事：这里的 `MaodouJson.forwardCompatible` 与 `ApiService.json`
// **不是同一个配置**——本模块是 `{ ignoreUnknownKeys = true, encodeDefaults = true,
// explicitNulls = false }`，而 app 里是 `Json { ignoreUnknownKeys = true }`。
// 两者对**请求体**的输出不同（encodeDefaults=true 会把默认值也写进 JSON、
// explicitNulls=false 会把显式 null 去掉）。所以「把 ApiService 换成 MaodouJson」
// 不是重构，是**改线上报文格式**——需要先有服务端兼容性验证，别顺手换。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
