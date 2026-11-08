import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Telegram behaviour (auth flow, chat list, pagination, lifecycle) written against the
// TelegramClient port. Pure Kotlin so it is unit-tested on the JVM with a fake client.
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
    api(projects.telegram.api)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
