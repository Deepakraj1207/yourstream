package dev.yourstream.desktop

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The build keeps only the icons the source names and only this platform's TDLib (see
 * desktop/build.gradle.kts, SlimJar). These tests run against those slimmed jars.
 */
class SlimJarsTest {
    @Test
    fun everyIconTheSourceUsesIsStillThere() {
        val names = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""Icons\.(Default|Filled|AutoMirrored\.Filled)\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }
        }.toSet()
        assertTrue(names.size >= 10, "found the icons: $names")
        val loader = javaClass.classLoader
        for ((set, name) in names) {
            val pkg = if (set.startsWith("AutoMirrored")) "automirrored.filled" else "filled"
            try {
                Class.forName("androidx.compose.material.icons.$pkg.${name}Kt", true, loader)
            } catch (e: ClassNotFoundException) {
                fail("icon $set.$name is missing from the app: ${e.message}")
            }
        }
    }

    @Test
    fun unusedIconsAreLeftOut() {
        // A common icon the app does not use.
        val present = runCatching { Class.forName("androidx.compose.material.icons.filled.AccountBalanceKt") }.isSuccess
        assertTrue(!present, "the icon set was not slimmed")
    }

    @Test
    fun tdlibForOtherPlatformsIsLeftOut() {
        val loader = javaClass.classLoader
        val os = System.getProperty("os.name").lowercase()
        val mine = when { os.startsWith("windows") -> "windows"; os.startsWith("mac") -> "macos"; else -> "linux" }
        val others = listOf("linux/x64/libtdjsonjava.so", "linux/arm64/libtdjsonjava.so", "macos/x64/libtdjsonjava.dylib",
            "macos/arm64/libtdjsonjava.dylib", "windows/x64/tdjsonjava.dll", "windows/arm64/tdjsonjava.dll")
        val present = others.filter { loader.getResource(it) != null }
        assertTrue(present.size == 1 && present.single().startsWith(mine), "only this platform's TDLib: $present")
    }
}
