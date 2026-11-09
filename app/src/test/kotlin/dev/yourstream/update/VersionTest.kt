package dev.yourstream.update

import org.junit.Assert.assertFalse
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
}
