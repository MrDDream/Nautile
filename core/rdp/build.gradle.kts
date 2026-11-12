plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nautile.rdp"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/java", "src/main/kotlin")
    // jniLibs/<abi>/ are prebuilt by scripts/build-freerdp.sh (FreeRDP 3, OpenSSL, cJSON, uriparser).
}

dependencies {
    testImplementation(libs.junit)
}
