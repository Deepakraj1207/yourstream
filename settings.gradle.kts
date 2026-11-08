pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "YourStream"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:common")
include(":telegram:api")
include(":telegram:core")
include(":telegram:tdlib")
include(":core:database")
include(":media:api")
include(":media:filename")
include(":media:parser")
include(":media:indexer")
include(":library")
include(":core:network")
include(":metadata:api")
include(":metadata:core")
include(":metadata:tmdb")
include(":metadata:cinemeta")
include(":metadata:sync")
include(":catalog:api")
include(":catalog:stremio")
include(":catalog:core")
include(":catalog:indian")
include(":catalog:sync")
include(":playback:stream")
include(":playback:testing")
include(":playback:media3")
include(":playback:adaptive")
include(":playback:tracks")
include(":core:language")
include(":core:diagnostics")
include(":search:core")
include(":search:engine")
include(":assistant:core")
include(":assistant:engine")
include(":watch:core")
include(":watch:engine")
