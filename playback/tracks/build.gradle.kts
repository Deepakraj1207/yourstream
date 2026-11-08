import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Audio/subtitle track intelligence: language normalization and detection (metadata is not
// trusted blindly), preference-based selection with fallbacks, remembered per-movie/per-series
// choices, external subtitle files. Pure Kotlin, independent of UI and of Media3.
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
    api(libs.kotlinx.coroutines.core)
    api(projects.core.language)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
