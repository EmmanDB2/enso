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
        // GeckoView and Android Components are only published here.
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroupByRegex("org\\.mozilla(\\..+)?") }
        }
    }
}

rootProject.name = "Enso"
include(":app")
