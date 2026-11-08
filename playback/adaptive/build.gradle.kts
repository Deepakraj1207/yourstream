import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Adaptive streaming for unreliable mobile networks: network monitor + bandwidth estimation,
// adaptive buffer policy with rebuffer prediction, seek coalescing, next-episode prefetch and a
// download governor (concurrency limits). Pure Kotlin; Media3 glue lives in :playback:media3.
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
    api(projects.playback.stream)
    api(projects.telegram.api)
    api(libs.kotlinx.coroutines.core)
    testImplementation(projects.playback.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Unit tests exclude the benchmarks (they take minutes); run them with ./gradlew :playback:adaptive:benchmark
tasks.named<Test>("test") { exclude("**/*Benchmark*") }

tasks.register<Test>("benchmark") {
    description = "Runs the adaptive streaming benchmarks (baseline vs adaptive on simulated network traces)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*Benchmark*")
    outputs.upToDateWhen { false }
    maxHeapSize = "1g"
    testLogging { showStandardStreams = true }
}
