import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Public Telegram contracts for the rest of the app. Pure Kotlin: no Android, no TDLib.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    explicitApi()
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
}
