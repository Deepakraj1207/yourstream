import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Filename parsing, media classification and normalisation. Pure functions, pure Kotlin.
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
    api(projects.media.api)
    api(projects.telegram.api)
    api(projects.media.filename)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
