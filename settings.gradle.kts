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
        // The Rokid Nexus bus-client SDK is built by JitPack from a tag or commit.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NewsNexus"
include(":app")
