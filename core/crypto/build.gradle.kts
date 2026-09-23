plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}


dependencies {
    api(project(":core:model"))

    testImplementation("junit:junit:4.13.2")
}

