plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nautile.security"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:i18n"))
    api(libs.androidx.biometric)
    // biometric 1.1.0 pulls fragment 1.2.5, whose FragmentActivity rejects the 32-bit request
    // codes of the Activity Result API (crash on permission and file-picker launches).
    api(libs.androidx.fragment.ktx)
    testImplementation(libs.junit)
}
