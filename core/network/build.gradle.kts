plugins {
    id("org.jetbrains.kotlin.jvm")
}


kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation(project(":core:model"))
}
