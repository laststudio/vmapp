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

rootProject.name = "ZeroBox"
include(":zerobox-sdk")
include(":zerobox-runtime")
include(":zerobox-xposed-bridge")
include(":sample-module")
include(":app")
