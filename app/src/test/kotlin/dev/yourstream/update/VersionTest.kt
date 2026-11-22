package dev.yourstream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun comparesReleaseVersions() {
        assertTrue(GitHubAppUpdates.newer("0.5.0", "0.4.0"))
        assertTrue(GitHubAppUpdates.newer("v0.10.0", "0.9.2"))
        assertTrue(GitHubAppUpdates.newer("1.0", "0.9.9"))
        assertFalse(GitHubAppUpdates.newer("0.4.0", "0.4.0"))
        assertFalse(GitHubAppUpdates.newer("0.3.9", "0.4.0"))
        assertFalse(GitHubAppUpdates.newer("0.4", "0.4.0"))
    }

    @Test
    fun picksTheApkForThisDevice() {
        val assets = listOf("YourStream-v0.8.0.apk", "SHA256SUMS.txt", "YourStream-v0.8.0-arm64-v8a.apk", "YourStream-v0.8.0-armeabi-v7a.apk",
            "YourStream-v0.8.0-x86_64.apk", "YourStream-v0.8.0-x86.apk")
        assertEquals(2, GitHubAppUpdates.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(3, GitHubAppUpdates.pickApk(assets, listOf("armeabi-v7a", "armeabi")))
        assertEquals("x86 is not mistaken for x86_64", 5, GitHubAppUpdates.pickApk(assets, listOf("x86")))
        assertEquals("no matching split: the universal APK", 0, GitHubAppUpdates.pickApk(assets, listOf("riscv64")))
        assertEquals("releases before splits", 0, GitHubAppUpdates.pickApk(listOf("YourStream-v0.7.0.apk", "SHA256SUMS.txt"), listOf("arm64-v8a")))
        assertNull(GitHubAppUpdates.pickApk(listOf("SHA256SUMS.txt"), listOf("arm64-v8a")))
        // Since 0.13.0 the universal APK is named "-universal" and a Windows installer sits beside it.
        val named = listOf("YourStream-v0.13.0-universal.apk", "SHA256SUMS.txt", "YourStream-v0.13.0-arm64-v8a.apk", "YourStream-v0.13.0-windows-x64.msi", "SHA256SUMS-windows.txt")
        assertEquals("a split for this device", 2, GitHubAppUpdates.pickApk(named, listOf("arm64-v8a")))
        assertEquals("the universal APK, never the installer", 0, GitHubAppUpdates.pickApk(named, listOf("riscv64")))
    }
}
