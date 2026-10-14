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

rootProject.name = "treadz-console"
include(":app")
include(":treadmill")
include(":treadmill-android")
include(":programs")
include(":hrm")
include(":hrm-android")
include(":userdata")
include(":sync")
