plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.termux.view"
    compileSdk = 37
    // Java only: no Kotlin compilation or standard library added by AGP's built-in Kotlin.
    enableKotlin = false
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    api(project(":third_party:terminal-emulator"))
}
