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
    ":core:realtime",
    ":core:crypto",
    ":core:session",
    ":core:testing",
    ":domain:messaging",
    ":domain:conversation",
    ":domain:groups",
    ":domain:calls",
    ":feature:chat",
    ":feature:contacts",
    ":feature:explore",
    ":feature:settings",
)
