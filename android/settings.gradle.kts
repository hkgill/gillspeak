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
rootProject.name = "gillspeak-android"
include(":app")
include(":agent-status") // what the watch reads from `gillspeak agents serve`, as plain Kotlin
include(":watch") // Wear OS app: fetches the agent status and serves it as complications
include(":watchface") // the agent watch face (Watch Face Format, no code)
