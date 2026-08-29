pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Maodouchat"
include(":app")
include(
    ":core:model",
    ":core:util",
    ":core:serialization",
    ":core:database",
    ":core:network",
    ":core:crypto",
    ":core:session",
    ":domain:messaging",
    ":feature:chat",
)
