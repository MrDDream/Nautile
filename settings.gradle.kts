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
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}

rootProject.name = "Nautile"
include(":app")
include(":core:data")
include(":core:security")
include(":core:ssh")
include(":core:i18n")
include(":core:rdp")
include(":third_party:terminal-emulator")
include(":third_party:terminal-view")
