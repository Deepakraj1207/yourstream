// Applies external metadata to the library: Room-backed cache, item matching, review queue,
// background sync. Providers are plugged in through Hilt; none is hard-coded here.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.yourstream.metadata.sync"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(projects.metadata.core)
    api(projects.media.api)
    implementation(projects.metadata.tmdb)
    implementation(projects.metadata.cinemeta)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
