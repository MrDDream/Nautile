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
        // JitPack builds from source on demand: only usb-serial-for-android comes from it, and it is
        // never looked up elsewhere.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "Nautile"
include(":app")
include(":core:data")
include(":core:security")
include(":core:ssh")
include(":core:i18n")
include(":core:rdp")
include(":core:vnc")
include(":third_party:terminal-emulator")
include(":third_party:terminal-view")
include(":third_party:terminal-pty-android")
