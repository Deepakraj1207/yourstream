import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Language codes and names (ISO 639-1/2/3, BCP-47, native scripts, release-name abbreviations).
// Shared by track selection and search. Pure Kotlin.
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
