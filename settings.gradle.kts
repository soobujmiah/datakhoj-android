// DataKhoj — Copyright (C) 2026 soobujmiah — AGPL-3.0-or-later. See LICENSE.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "datakhoj-android"
include(":core", ":app")
