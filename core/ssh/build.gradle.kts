plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    api(libs.sshj)
    implementation(project(":core:i18n"))
    api(libs.eddsa)
    implementation(libs.commons.net)
    api(libs.bouncycastle.prov)
    api(libs.bouncycastle.pkix)
    implementation(libs.slf4j.nop)
    testImplementation(libs.junit)
}

tasks.test {
    // Integration tests need Docker; enable with -PsshIT.
    systemProperty("nautile.sshIT", project.hasProperty("sshIT"))
}
