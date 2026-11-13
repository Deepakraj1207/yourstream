// Media3 playback of Telegram media: TelegramMediaDataSource (range reads while downloading),
// TelegramMediaSourceFactory, the library -> Telegram file resolver and a player controller with
// state, retry and metrics. Only :telegram:api is used: TDLib stays in :telegram:tdlib.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.yourstream.playback.media3"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(projects.playback.stream)
    api(projects.playback.adaptive)
    api(projects.playback.tracks)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.okhttp)
    implementation(projects.core.network)
    // Loaded by DefaultRenderersFactory by class name when extension renderers are on.
    implementation(libs.media3.ffmpeg)
    implementation(projects.library)
    implementation(projects.core.database)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(projects.playback.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.androidx.media3.session)
}
