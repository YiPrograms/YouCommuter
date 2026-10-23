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
rootProject.name = "YouCommuter"
include(":app", ":core:model", ":core:domain", ":core:data", ":core:database", ":core:platform", ":core:ui")
include(":feature:commute", ":feature:stations", ":feature:routes")
include(":core:map")
