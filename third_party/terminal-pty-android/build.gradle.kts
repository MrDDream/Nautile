// The local shell's PTY on Android (JNI helper from Termux).
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.termux.terminal.pty"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    // Java only: no Kotlin compilation or standard library added by AGP's built-in Kotlin.
    enableKotlin = false
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fstack-protector-strong", "-Wl,--gc-sections") }
        }
        ndk { abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a") }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { api(project(":third_party:terminal-emulator")) }
