package dev.yourstream.desktop

import dev.yourstream.desktop.playback.TrackLabels
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackLabelsTest {
    @Test
    fun audioNames() {
        assertEquals("Tamil · AC3 5.1", TrackLabels.audio(1, "Track 1 - [Tamil]", "tam", null, "a52 ", 6))
        assertEquals("Hindi · Original · E-AC3 7.1", TrackLabels.audio(2, null, "hi", "Original", "eac3", 8))
        assertEquals("English · AAC Stereo", TrackLabels.audio(1, null, "eng", "English", "mp4a", 2))
        assertEquals("Telugu", TrackLabels.audio(3, "Track 3 - [Telugu]", null, null, null, null))
        assertEquals("Track 4", TrackLabels.audio(4, null, "und", null, null, null))
        assertEquals("French · DTS 5.1", TrackLabels.audio(1, null, "fre", null, "dts ", 6))
    }

    @Test
    fun languageCodes() {
        assertEquals("ta", TrackLabels.code("tam"))
        assertEquals("ta", TrackLabels.code("TA"))
        assertEquals("de", TrackLabels.code("ger"))
        assertEquals("ml", TrackLabels.code("Malayalam"))
        assertNull(TrackLabels.code("und"))
        assertNull(TrackLabels.code(""))
    }

    @Test
    fun subtitleNames() {
        assertEquals("English · SDH", TrackLabels.subtitle(1, null, "eng", "SDH"))
        assertEquals("Subtitles 2", TrackLabels.subtitle(2, null, null, null))
    }
}
