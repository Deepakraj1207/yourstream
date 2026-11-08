// The offline library search engine: a denormalized search index in SQLite (kept fresh by
// triggers), FTS candidate retrieval, fuzzy expansion against the library vocabulary, ranking,
// filters, sorting and pagination. No network.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.yourstream.search.engine"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Full-size benchmark only on request: ./gradlew :search:engine:testDebugUnitTest -Psearch.benchmark
            if (project.hasProperty("search.benchmark")) test.systemProperty("search.benchmark", "true")
            test.maxHeapSize = "2g"
            test.testLogging { showStandardStreams = project.hasProperty("search.benchmark") }
        }
    }
}

dependencies {
    api(projects.search.core)
    implementation(projects.core.database)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
