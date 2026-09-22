plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // 架构测试需要扫描其它 core/domain 模块的已编译类。
    //
    // ⚠️ G222b 查实的覆盖面（**结构性限制，不是疏忽**）：
    // 本模块是纯 JVM（org.jetbrains.kotlin.jvm），只能依赖同平台的模块。
    // 而 core/crypto、core/network、core/realtime、core/session 是
    // **Android library**（com.android.library，platform-type = androidJvm）——
    // 往这里加依赖会直接「No matching variant」编译失败。
    // 所以 A01 的 ArchUnit 规则对那 4 个 Android core 模块**结构上照不到**，
    // 只能靠 :app 侧的 ClientArchitectureTest 补（它扫 app/src/main，而 app 依赖这些模块）。
    // `core/util` 是 JVM 且此前漏在表外，G222b 已补。
    testImplementation(project(":core:util"))
    testImplementation(project(":core:serialization"))
    testImplementation(project(":domain:messaging"))
    testImplementation(project(":domain:conversation"))
    testImplementation(project(":domain:groups"))
    testImplementation(project(":domain:calls"))
}

tasks.test {
    useJUnitPlatform()

    // ClientHotspotRatchetTest 在运行期扫描 app 源码（热点行数、UI 直连持久层的次数）。
    // 不把这些路径声明成输入的话，Gradle 会认为 test 是 UP-TO-DATE——
    // 实测踩过：往热点文件里加了一行、又给 UI 加了一处 `database.`，`gradlew :core:testing:test`
    // 660ms「BUILD SUCCESSFUL」，两个棘轮一个都没跑。声明成输入后改源码会立刻重跑。
    inputs.dir(rootProject.file("app/src/main/java/com/maodouchat/ui"))
        .withPropertyName("uiSourcesForRatchet")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    listOf(
        "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt",
        "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt",
        "app/src/main/java/com/maodouchat/util/GroupPlayPolicy.kt",
    ).forEach { relative ->
        inputs.file(rootProject.file(relative))
            .withPropertyName("hotspot:" + relative.substringAfterLast('/'))
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
