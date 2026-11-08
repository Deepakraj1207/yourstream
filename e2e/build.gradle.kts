// End-to-end functional audit: the real modules wired together as the app wires them, with only
// Telegram replaced (fake chats + simulated file transfers) and providers faked. Test-only.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.yourstream.e2e"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "3g"
            test.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    testImplementation(projects.core.common)
    testImplementation(projects.core.database)
    testImplementation(projects.core.diagnostics)
    testImplementation(projects.telegram.api)
    testImplementation(projects.media.parser)
    testImplementation(projects.media.indexer)
    testImplementation(projects.library)
    testImplementation(projects.metadata.sync)
    testImplementation(projects.catalog.sync)
    testImplementation(projects.search.engine)
    testImplementation(projects.assistant.engine)
    testImplementation(projects.watch.engine)
    testImplementation(projects.playback.media3)
    testImplementation(projects.playback.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}
