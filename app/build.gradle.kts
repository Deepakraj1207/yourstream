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
