plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}


dependencies {
    api(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // app 用同版本 libsignal-android AAR（含同一批 org.signal 类），implementation 会重复类。
    compileOnly("org.signal:libsignal-client:0.41.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.signal:libsignal-client:0.41.0")
}

