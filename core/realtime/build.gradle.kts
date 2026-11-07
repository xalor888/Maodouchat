plugins {
    id("org.jetbrains.kotlin.jvm")
}


kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":core:model"))
    // RealtimeConnectionManager 依赖会话句柄 SessionContext /
    // SessionContextProvider（:core:session 只依赖 :core:model，无环）。
    implementation(project(":core:session"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
