import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Telegram API credentials. NEVER committed. Looked up in this order, first hit wins:
 *  1. Gradle property      -Ptelegram.apiId=... / ~/.gradle/gradle.properties
 *  2. Environment variable TELEGRAM_API_ID / TELEGRAM_API_HASH (CI)
 *  3. secrets.properties   in the project root (git-ignored)
 *  4. local.properties     in the project root (git-ignored)
 * Missing values build fine; the app then reports "Telegram is not configured" at runtime.
 * See docs/TELEGRAM_SETUP.md.
 */
fun secret(propertyKey: String, envKey: String): String {
    providers.gradleProperty(propertyKey).orNull?.let { return it.trim() }
    providers.environmentVariable(envKey).orNull?.let { return it.trim() }
    for (fileName in listOf("secrets.properties", "local.properties")) {
        val file = rootProject.file(fileName)
        if (file.isFile) {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            props.getProperty(propertyKey)?.let { return it.trim() }
        }
    }
    return ""
}

val telegramApiId = secret("telegram.apiId", "TELEGRAM_API_ID").ifEmpty { "0" }
val telegramApiHash = secret("telegram.apiHash", "TELEGRAM_API_HASH")
val telegramUseTestDc = secret("telegram.useTestDc", "TELEGRAM_USE_TEST_DC").ifEmpty { "false" }

// Metadata providers (docs/METADATA.md). TMDB is optional: without a key, Cinemeta alone is used.
val tmdbApiKey = secret("tmdb.apiKey", "TMDB_API_KEY")
val tmdbReadToken = secret("tmdb.readToken", "TMDB_READ_TOKEN")
val metadataProviders = secret("metadata.providers", "METADATA_PROVIDERS").ifEmpty { "tmdb,cinemeta" }
val metadataLanguage = secret("metadata.language", "METADATA_LANGUAGE").ifEmpty { "en" }
// Catalog providers (docs/CATALOGS.md): optional title lists, never a media source. Empty
// ("none") leaves the local library only. Custom add-ons: comma-separated manifest URLs.
val catalogProviders = secret("catalog.providers", "CATALOG_PROVIDERS").ifEmpty { "indiastreams,indian_regional" }
val catalogCustomManifests = secret("catalog.customManifests", "CATALOG_CUSTOM_MANIFESTS")
// Playback (docs/ADAPTIVE_STREAMING.md): adaptive streaming on/off, download concurrency, prefetch on mobile data.
val playbackAdaptive = secret("playback.adaptive", "PLAYBACK_ADAPTIVE").ifEmpty { "true" }
val playbackMaxDownloads = secret("playback.maxConcurrentDownloads", "PLAYBACK_MAX_CONCURRENT_DOWNLOADS").ifEmpty { "2" }
val playbackPrefetchOnMobile = secret("playback.prefetchOnMobileData", "PLAYBACK_PREFETCH_ON_MOBILE_DATA").ifEmpty { "false" }

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

require(telegramApiId.toIntOrNull() != null) { "telegram.apiId must be an integer, got '$telegramApiId'" }
require(telegramApiHash.matches(Regex("[0-9a-fA-F]*"))) { "telegram.apiHash must be hexadecimal" }

android {
    namespace = "dev.yourstream"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.yourstream"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("boolean", "TELEGRAM_USE_TEST_DC", telegramUseTestDc.toBoolean().toString())
        buildConfigField("String", "TMDB_API_KEY", quoted(tmdbApiKey))
        buildConfigField("String", "TMDB_READ_TOKEN", quoted(tmdbReadToken))
        buildConfigField("String", "METADATA_PROVIDERS", quoted(metadataProviders))
        buildConfigField("String", "METADATA_LANGUAGE", quoted(metadataLanguage))
        buildConfigField("String", "CATALOG_PROVIDERS", quoted(catalogProviders))
        buildConfigField("String", "CATALOG_CUSTOM_MANIFESTS", quoted(catalogCustomManifests))
        buildConfigField("boolean", "PLAYBACK_ADAPTIVE", playbackAdaptive.toBoolean().toString())
        buildConfigField("int", "PLAYBACK_MAX_CONCURRENT_DOWNLOADS", (playbackMaxDownloads.toIntOrNull() ?: 2).coerceIn(1, 4).toString())
        buildConfigField("boolean", "PLAYBACK_PREFETCH_ON_MOBILE_DATA", playbackPrefetchOnMobile.toBoolean().toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(projects.telegram.tdlib)
    implementation(projects.media.indexer)
    implementation(projects.media.filename)
    implementation(projects.library)
    implementation(projects.metadata.sync)
    implementation(projects.metadata.tmdb)
    implementation(projects.catalog.sync)
    implementation(projects.catalog.indian)
    implementation(projects.playback.media3)
    implementation(projects.search.engine)
    implementation(projects.assistant.engine)
    implementation(projects.watch.engine)
    implementation(projects.ui.screens)
    implementation(projects.core.diagnostics)
    implementation(projects.core.database)
    implementation(libs.androidx.media3.ui)
    implementation(projects.core.network)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.zxing.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
