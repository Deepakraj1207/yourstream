// YourStream for Windows (and, from the same code, Linux and macOS): a JVM desktop app built with
// Compose Multiplatform. It reuses the plain-Kotlin modules unchanged and replaces only what was
// Android-only: TDLib's Android build (tdl-coroutines' JVM build carries the Windows natives),
// Room (a small file store), WorkManager (coroutines), Media3 (vlcj over an installed VLC) and
// the Android screens. See docs/WINDOWS.md.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/*
 * Plain-Kotlin files that live in Android library modules, compiled into this app as they are:
 * the TDLib client (the only TDLib-facing class; it uses no Android API) and the indexing engine
 * with its store contract. Copying them at build time keeps one copy of the code, and keeps the
 * Android modules (and their Hilt/Room wiring) exactly as they were.
 */
val sharedAndroidSources = tasks.register<Sync>("sharedAndroidSources") {
    from(rootProject.file("telegram/tdlib/src/main/kotlin")) {
        include("dev/yourstream/telegram/tdlib/TdlibTelegramClient.kt", "dev/yourstream/telegram/tdlib/TdMappers.kt")
    }
    from(rootProject.file("media/indexer/src/main/kotlin")) {
        include("dev/yourstream/media/indexer/engine/**", "dev/yourstream/media/indexer/store/IndexStore.kt")
    }
    into(layout.buildDirectory.dir("generated/sharedAndroidSources/kotlin"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(sharedAndroidSources) }

/** The manga style's lettering (Bangers, SIL OFL), the same file the Android app uses. */
val sharedFonts = tasks.register<Sync>("sharedFonts") {
    from(rootProject.file("ui/core/src/main/res/font")) { include("bangers.ttf") }
    into(layout.buildDirectory.dir("generated/sharedFonts/fonts"))
}

/** Telegram API credentials, from the same places as the Android app (docs/TELEGRAM_SETUP.md). */
fun secret(property: String, env: String): String {
    providers.gradleProperty(property).orNull?.let { return it }
    providers.environmentVariable(env).orNull?.let { return it }
    for (name in listOf("secrets.properties", "local.properties")) {
        val f = rootProject.file(name)
        if (f.isFile) {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            val value: String? = p.getProperty(property)
            if (value != null) return value
        }
    }
    return ""
}

val desktopVersion = providers.gradleProperty("desktop.version").orElse("0.12.4")

/** The credentials and version go into a resource read at start; nothing is printed or logged. */
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    val apiId = secret("telegram.apiId", "TELEGRAM_API_ID").ifEmpty { "0" }
    val apiHash = secret("telegram.apiHash", "TELEGRAM_API_HASH")
    val version = desktopVersion
    inputs.property("apiId", apiId)
    inputs.property("apiHash", apiHash)
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("yourstream-build.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("telegram.apiId=$apiId\ntelegram.apiHash=$apiHash\nversion=${version.get()}\n")
    }
}
sourceSets.named("main") {
    resources.srcDir(buildInfo)
    resources.srcDir(sharedFonts.map { it.destinationDir.parentFile })
}

/*
 * Smaller installer: two big jars are slimmed on their way into the app (and its tests).
 *  - tdl-coroutines' JVM jar carries TDLib for six platforms (111 MB); only this machine's is
 *    kept (the Windows installer is built on Windows x64, so it keeps windows/x64).
 *  - material-icons-extended holds ~11,000 icons (36 MB); only those the app's source uses are
 *    kept. IconsTest checks that every icon the source names is still there.
 */
abstract class SlimJar : TransformAction<SlimJar.Params> {
    interface Params : TransformParameters {
        @get:Input val nativeDir: Property<String>
        @get:Input val icons: SetProperty<String>
    }

    @get:InputArtifact abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val jar = input.get().asFile
        val keep: ((String) -> Boolean)? = when {
            jar.name.startsWith("tdl-coroutines-jvm") -> { name ->
                !Regex("^(linux|macos|windows)/[^/]+/.+").matches(name) || name.startsWith(parameters.nativeDir.get())
            }
            jar.name.startsWith("material-icons-extended") -> { name ->
                !name.startsWith("androidx/compose/material/icons/") || name.endsWith("/") || name in parameters.icons.get()
            }
            else -> null
        }
        if (keep == null) { outputs.file(input); return }
        val out = outputs.file("slim-${jar.name}")
        ZipFile(jar).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (entry in zip.entries()) {
                    if (!keep(entry.name)) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}

val slim = Attribute.of("yourstream.slim", Boolean::class.javaObjectType)
val thisPlatformNatives = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osDir = when { os.startsWith("windows") -> "windows"; os.startsWith("mac") -> "macos"; else -> "linux" }
    "$osDir/${if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"}/"
}
/** Icons named in the app's source: Icons.Default.X / Icons.Filled.X / Icons.AutoMirrored.Filled.X. */
val usedIcons = fileTree("src/main/kotlin").files.flatMap { f ->
    Regex("""Icons\.(Default|Filled|AutoMirrored\.Filled)\.(\w+)""").findAll(f.readText()).map { m ->
        val pkg = if (m.groupValues[1].startsWith("AutoMirrored")) "automirrored/filled" else "filled"
        "androidx/compose/material/icons/$pkg/${m.groupValues[2]}Kt.class"
    }.toList()
}.toSet()
dependencies {
    attributesSchema { attribute(slim) }
    artifactTypes.getByName("jar") { attributes.attribute(slim, false) }
    registerTransform(SlimJar::class) {
        from.attribute(slim, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(slim, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        parameters { nativeDir.set(thisPlatformNatives); icons.set(usedIcons) }
    }
}
listOf("runtimeClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes.attribute(slim, true) }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.language)
    implementation(projects.telegram.api)
    implementation(projects.telegram.core)
    implementation(projects.media.api)
    implementation(projects.media.filename)
    implementation(projects.media.parser)
    implementation(projects.metadata.api)
    implementation(projects.metadata.core)
    implementation(projects.metadata.tmdb)
    implementation(projects.metadata.cinemeta)
    implementation(projects.core.network)
    implementation(projects.playback.stream)

    implementation(libs.tdl.coroutines)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.zxing.core)
    implementation(libs.vlcj)

    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.material3)
    implementation(libs.cmp.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(projects.playback.testing)
    testImplementation(libs.cmp.ui.test.junit4)
}

// VLC shipped with the installer (desktop/scripts/fetch-vlc-windows.sh puts it here on the Windows
// build machine). Tests use it too, so CI's Windows job plays videos with that same copy.
val appResources = layout.projectDirectory.dir("appResources")
tasks.named<Test>("test") {
    // A hung native call (TDLib, VLC) must fail the build, not stall it: each test also has its
    // own time limit, and the log names every test as it runs.
    timeout.set(Duration.ofMinutes(8))
    // Opt-in measurements (StreamingBenchmark): -Dbench=true, -Dbench.clip=..., -Dbench.mbps=...
    systemProperties(providers.systemPropertiesPrefixedBy("bench").get())
    testLogging { events("started", "passed", "skipped", "failed"); exceptionFormat = TestExceptionFormat.FULL }
    val osDir = appResources.dir(if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux").asFile
    if (osDir.isDirectory) systemProperty("compose.application.resources.dir", osDir.absolutePath)
}

compose.desktop {
    application {
        mainClass = "dev.yourstream.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            appResourcesRootDir.set(appResources)
            packageName = "YourStream"
            packageVersion = desktopVersion.get()
            description = "Your Telegram videos as a private movie & series library"
            vendor = "YourStream"
            licenseFile.set(rootProject.file("LICENSE"))
            // java.net.http and jdk.httpserver: the local stream server VLC reads from.
            modules("java.net.http", "jdk.httpserver", "java.sql", "jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                menuGroup = "YourStream"
                // Fixed, so a newer installer upgrades the installed app instead of adding a second.
                upgradeUuid = "6f1c2a4e-5b7d-4e8a-9c3f-2d1e0b7a6c54"
                iconFile.set(project.file("icons/yourstream.ico"))
            }
        }
    }
}
