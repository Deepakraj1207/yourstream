package dev.yourstream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The updater's check of a downloaded APK against the release's SHA256SUMS.txt. */
class ChecksumTest {
    @Test
    fun readsTheReleaseWorkflowsChecksumFile() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        // As `sha256sum *.apk` writes it in .github/workflows/release.yml (text and binary mode).
        val sums = "$a  YourStream-v0.11.0.apk\n$b *YourStream-v0.11.0-arm64-v8a.apk\n\nnot a checksum line\n"
        val parsed = GitHubAppUpdates.parseChecksums(sums)
        assertEquals(a, parsed["YourStream-v0.11.0.apk"])
        assertEquals("b".repeat(64), parsed["YourStream-v0.11.0-arm64-v8a.apk"])
        assertNull(parsed["YourStream-v0.11.0-x86.apk"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun hashesTheFileLikeSha256sum() {
        val f = File.createTempFile("apk", ".bin").apply { writeText("abc"); deleteOnExit() }
        // sha256("abc"), the FIPS 180-2 test vector.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", GitHubAppUpdates.sha256(f))
    }
}
