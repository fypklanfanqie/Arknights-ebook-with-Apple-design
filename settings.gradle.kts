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

rootProject.name = "ArknightsReader"
include(":app")
include(":reader:turn")
include(":reader:turngl")
include(":data:model")
include(":data:database")
include(":format:api")
include(":format:text")
include(":format:epub")
include(":feature:importer")
include(":feature:library")
include(":feature:reader")
include(":feature:settings")
include(":feature:design")
