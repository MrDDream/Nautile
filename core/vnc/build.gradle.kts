plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:i18n"))
    testImplementation(libs.junit)
}

tasks.test {
    // Integration test against a VNC server in Docker; enable with -PvncIT.
    systemProperty("nautile.vncIT", project.hasProperty("vncIT"))
}
