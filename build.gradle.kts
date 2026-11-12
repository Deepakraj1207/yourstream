plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

/**
 * One KSP task at a time across all modules. CI builds intermittently produced a library jar
 * without its KSP-generated Hilt metadata (a different module each time: playback:media3,
 * catalog:sync, assistant:engine, media:indexer, watch:engine), so the app's Hilt graph missed
 * that module's bindings. KSP2 runs inside the Gradle process; serialising its tasks removes
 * concurrent runs from the picture (docs/IMPLEMENTATION_PROGRESS.md, Phase 1, Hilt/KSP).
 */
abstract class KspSerialService : BuildService<BuildServiceParameters.None>

val kspSerial = gradle.sharedServices.registerIfAbsent("kspSerial", KspSerialService::class) { maxParallelUsages.set(1) }

subprojects {
    tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach { usesService(kspSerial) }
}
