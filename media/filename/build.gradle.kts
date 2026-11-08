import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Standalone filename → media metadata library. No dependencies besides the Kotlin stdlib, so it
// can evolve (and be tested) without touching Telegram, storage or Android code.
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
