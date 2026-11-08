import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Diagnostics, pure JVM so it works even when Android parts (database, Telegram) are broken:
// redaction, persistent rotating logs, an event journal, crash reports with crash-loop
// detection, health checks and the diagnostic report.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
