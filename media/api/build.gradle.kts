import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Media index contracts and models. Pure Kotlin (paging-common is multiplatform).
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
    api(libs.androidx.paging.common)
}
