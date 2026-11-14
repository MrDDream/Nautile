import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nautile.rdp"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // jniLibs/<abi>/ are prebuilt by scripts/build-freerdp.sh (FreeRDP 3, OpenSSL, cJSON, uriparser).
}

dependencies {
    testImplementation(libs.junit)
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
