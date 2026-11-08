package dev.yourstream.media.parser

import dev.yourstream.media.api.model.MediaType
import dev.yourstream.telegram.api.model.TelegramMediaContentType.AUDIO
import dev.yourstream.telegram.api.model.TelegramMediaContentType.DOCUMENT
import dev.yourstream.telegram.api.model.TelegramMediaContentType.VIDEO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaClassifierAndTextTest {

    @Test
    fun documentsAreClassifiedByExtensionThenMime() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("movie.mkv", "application/octet-stream", DOCUMENT))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("movie.mkv.003", "application/octet-stream", DOCUMENT))
        assertEquals(MediaType.SUBTITLE, MediaClassifier.classify("movie.en.srt", "application/octet-stream", DOCUMENT))
        assertEquals(MediaType.SUBTITLE, MediaClassifier.classify("noext", "application/x-subrip", DOCUMENT))
        assertEquals(MediaType.AUDIO, MediaClassifier.classify("song.flac", "", DOCUMENT))
        assertEquals(MediaType.ARCHIVE, MediaClassifier.classify("pack.part2.rar", "", DOCUMENT))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("noext", "video/mp4", DOCUMENT))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("book.pdf", "application/pdf", DOCUMENT))
    }

    @Test
    fun telegramVideoAndAudioMessagesKeepTheirType() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("whatever.bin", "", VIDEO))
        assertEquals(MediaType.AUDIO, MediaClassifier.classify("x", "", AUDIO))
    }

    @Test
    fun splitNames() {
        val parts = MediaClassifier.splitName("Film.2020.mkv.002")
        assertEquals("Film.2020", parts.baseName)
        assertEquals("mkv", parts.extension)
        assertEquals(2, parts.splitPart)
        assertEquals(3, MediaClassifier.splitName("pack.part3.rar").splitPart)
        assertNull(MediaClassifier.splitName("Film.mkv").splitPart)
    }

    @Test
    fun searchTextFoldsLatinAccentsButKeepsIndicMarks() {
        assertEquals("amelie 2001 1080p", SearchText.normalize("Amélie.2001.[1080p]"))
        assertEquals("விக்ரம்", SearchText.normalize("விக்ரம்"))
        assertEquals("हिंदी फ़िल्म", SearchText.normalize("हिंदी_फ़िल्म"))
    }

    @Test
    fun ftsQueryIsPrefixAndSafe() {
        assertEquals("aven* endg*", SearchText.ftsQuery("Aven  Endg"))
        // Quotes and stars are dropped; lower-case "or" is a plain word, not an FTS operator.
        assertEquals("a* or* b*", SearchText.ftsQuery("a\" OR b*"))
        assertNull(SearchText.ftsQuery("  -- ** "))
    }

    @Test
    fun fingerprintNeedsSizeAndName() {
        assertNull(MediaNormalizer.fingerprint("film", "mkv", null, 10))
        assertEquals("2000000:film 2020:mkv:0", MediaNormalizer.fingerprint("Film.2020", "mkv", null, 2_000_000))
    }
}
