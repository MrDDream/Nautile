import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing: keystore.properties at the project root (ignored by git), otherwise the environment variables
 * NAUTILE_STORE_FILE, NAUTILE_STORE_PASSWORD, NAUTILE_KEY_ALIAS, NAUTILE_KEY_PASSWORD (CI).
 * Without either, the release variant is built unsigned.
 */
val signing: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        Properties().apply { file.inputStream().use { load(it) } }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
    } else {
        System.getenv("NAUTILE_STORE_FILE")?.let {
            mapOf(
                "storeFile" to it,
                "storePassword" to System.getenv("NAUTILE_STORE_PASSWORD").orEmpty(),
                "keyAlias" to System.getenv("NAUTILE_KEY_ALIAS").orEmpty(),
                "keyPassword" to System.getenv("NAUTILE_KEY_PASSWORD").orEmpty(),
            )
        }
    }
}

android {
    namespace = "com.nautile.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nautile"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.1"
    }

    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getValue("storeFile"))
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }

    // One APK per architecture (≈35 MB) plus a universal one: FreeRDP's native libraries weigh ~13 MB per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // APKs named Nautile-v<version>-<abi>-<variant>.apk
    base.archivesName.set("Nautile-v${defaultConfig.versionName}")

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("nautile.screenshots", project.findProperty("nautileScreenshots")?.toString().orEmpty()) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/BCprov-jdk18on.*", "META-INF/BCpkix-jdk18on.*", "META-INF/BCutil-jdk18on.*")
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:security"))
    implementation(project(":core:ssh"))
    implementation(project(":core:i18n"))
    implementation(project(":core:rdp"))
    implementation(project(":third_party:terminal-view"))
    implementation(libs.usb.serial)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.anthropic.java)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Screenshots of the real screens for the README and the website (scripts/screenshots.sh).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
