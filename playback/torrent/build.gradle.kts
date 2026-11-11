// Torrent streams from stream add-ons, played while they download: libtorrent (libtorrent4j, MIT)
// fetches the chosen file around the playback position, with deadlines on the pieces the player
// needs next, and Media3 reads it through TorrentDataSource. Nothing here knows about add-ons.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.yourstream.playback.torrent"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    implementation(libs.libtorrent4j)
    // The native library for each ABI the app ships (lib/<abi>/libtorrent4j.so inside each jar).
    runtimeOnly(libs.libtorrent4j.android.arm64)
    runtimeOnly(libs.libtorrent4j.android.arm)
    runtimeOnly(libs.libtorrent4j.android.amd64)
    runtimeOnly(libs.libtorrent4j.android.x86)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // libtorrent's Linux build: the engine streams from a local seed in a JVM test.
    testImplementation(libs.libtorrent4j.linux)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
