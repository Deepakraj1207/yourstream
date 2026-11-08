import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Random-access streaming of Telegram files: reads any byte range while the file is still being
// downloaded, steering the download to where the reader is. Pure Kotlin, only :telegram:api.
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
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
    testImplementation(projects.playback.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
