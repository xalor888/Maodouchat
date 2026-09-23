plugins {
    id("org.jetbrains.kotlin.jvm")
}


kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
