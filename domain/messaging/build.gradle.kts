plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:serialization"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
