plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.termux.terminal"
    compileSdk = 34
    ndkVersion = "26.3.11579264"
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections") }
        }
        ndk { abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a") }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // src/main/jni: the PTY helper used by local shell sessions.
}
