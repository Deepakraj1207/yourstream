// Screens of the app: navigation graph, adaptive shell (phone, tablet, TV), and one
// contract (state + actions + ViewModel) per screen, connected to the existing domain layer.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.yourstream.ui.screens"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(projects.ui.core)
    implementation(projects.library)
    implementation(projects.watch.engine)
    implementation(projects.search.engine)
    implementation(projects.playback.media3)
    implementation(projects.playback.tracks)
    implementation(projects.telegram.api)
    implementation(projects.media.api)
    implementation(projects.core.diagnostics)
    implementation(projects.metadata.api)
    implementation(projects.metadata.core)
    implementation(projects.metadata.sync)
    implementation(projects.assistant.core)
    implementation(projects.assistant.engine)
    implementation(projects.sync)
    implementation(projects.catalog.sync)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.media3.ui)
    // Add-on streams are often HLS (.m3u8) or DASH.
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.dash)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
