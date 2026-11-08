import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test support for playback: a simulated Telegram file backend (range downloads with bandwidth,
// latency and outages, like TDLib) and synthetic test videos. Never used by the app.
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
    api(projects.telegram.api)
    api(libs.kotlinx.coroutines.core)
}
