package dev.yourstream.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What reaches logcat (readable by adb and bug reports) is redacted like the stored log. */
class LogcatRedactionTest {
    @Test
    fun phoneNumbersCodesAndKeysNeverReachLogcat() {
        val line = AppLogger.forLogcat("sending +919876543210, login code 12345, GET /3/movie?api_key=abcdef0123456789abcdef0123456789", null, debug = false)
        assertFalse(line, "9876543210" in line)
        assertFalse(line, "12345" in line)
        assertFalse(line, "abcdef0123456789" in line)
        assertTrue(line, line.startsWith("sending +91"))
    }

    @Test
    fun releaseBuildsAppendTheRedactedExceptionInsteadOfAStackTrace() {
        val e = java.io.IOException("failed https://api.example.org/x?token=supersecretvalue")
        val release = AppLogger.forLogcat("TMDB failed", e, debug = false)
        assertTrue(release, release.startsWith("TMDB failed (IOException: failed https://api.example.org/x?token=***"))
        assertFalse(release, "supersecretvalue" in release)
        // Debug builds pass the throwable itself (stack trace), so the message stays as written.
        assertEquals("TMDB failed", AppLogger.forLogcat("TMDB failed", e, debug = true))
    }
}
