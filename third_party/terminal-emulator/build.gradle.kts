// Pure JVM: the emulator is shared by the Android app and the desktop app.
// The local shell's PTY is platform code (third_party/terminal-pty-android on Android, pty4j on desktop).
plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies { testImplementation(libs.junit) }
