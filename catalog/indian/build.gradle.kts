import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Optional Indian-focused providers (IndiaStreams, Indian Regional Catalog) as add-on definitions.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(projects.catalog.stremio)
    testImplementation(libs.ktor.client.mock)
    testImplementation(projects.catalog.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
