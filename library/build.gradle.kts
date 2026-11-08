// The local media library: movies, series → seasons → episodes, standalone videos, versions,
// watch history, favorites, subtitle/audio tracks. Built from the media index, stored in Room.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.yourstream.library"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(projects.core.common)
    api(projects.media.api)
    api(libs.androidx.paging.common)
    implementation(projects.core.database)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(projects.media.parser)
    testImplementation(projects.media.indexer)
    testImplementation(projects.telegram.api)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
}
