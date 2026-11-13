plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nautile.data"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Compatibility files for the desktop app: -PnautileFixtures writes them to desktop/tests/fixtures.
        unitTests.all { it.systemProperty("nautile.fixtures", if (project.hasProperty("nautileFixtures")) rootProject.file("desktop/tests/fixtures").path else "") }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(libs.androidx.room.runtime)
    implementation(project(":core:i18n"))
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.prov)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
