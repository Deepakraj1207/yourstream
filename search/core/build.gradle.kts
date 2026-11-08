import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Local search: normalization, query interpretation, fuzzy term matching (edit distance,
// transliteration keys, trigrams) and ranking. Pure Kotlin, no database, no network.
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
    api(projects.core.language)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
