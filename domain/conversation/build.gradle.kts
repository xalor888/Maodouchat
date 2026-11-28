plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:session"))
    implementation(project(":core:realtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // 测试专用：fuzz/oracle 用例复用了 contacts 与 messaging 的领域策略做差分校验。
    // checkArchitecture 只约束 implementation/api，这里不构成生产代码的 domain->domain 依赖。
    testImplementation(project(":domain:contacts"))
    testImplementation(project(":domain:messaging"))
}
