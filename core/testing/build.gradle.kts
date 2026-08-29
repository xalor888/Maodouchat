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

    // 架构测试需要扫描其它 core/domain 模块的已编译类
    testImplementation(project(":core:serialization"))
    testImplementation(project(":domain:messaging"))
    testImplementation(project(":domain:conversation"))
    testImplementation(project(":domain:groups"))
    testImplementation(project(":domain:calls"))
}

tasks.test {
    useJUnitPlatform()
}
