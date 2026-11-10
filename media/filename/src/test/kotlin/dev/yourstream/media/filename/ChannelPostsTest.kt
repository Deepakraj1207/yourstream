package dev.yourstream.media.filename

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files from movie channels: tags under the file, the title in the post above it. */
class ChannelPostsTest {
    private val post = "🎬 Bumblebee (2018)\nTelugu + Hindi | 480p 720p\nJoin @SPEEDY_LinkZ"
    private fun parse(name: String, caption: String? = null, context: String? = null, chat: String? = "Speedy Links Movies") =
        MediaFilenameParser.parse(name, ParseHints(caption = caption, chatTitle = chat, context = context))

    @Test
    fun languageTagsUnderAFileAreNotItsTitle() {
        for ((name, caption) in listOf("VID_20240101.mp4" to "Tel+Hin", "7744f81a5b3063b9.mp4" to "Tel + Hin 450MB", "AgADBQADq7Ex.mkv" to "Tel",
            "Tel+Hin.mkv" to null, "TR MOVIES Tamil.mkv" to null, "Dual Audio 480p.mkv" to null)) {
            val r = parse(name, caption, context = post)
            assertEquals("Bumblebee", r.title?.value, "$name | $caption")
            assertEquals(2018, r.year?.value)
            assertTrue(MediaFilenameParser.CONTEXT_WARNING in r.warnings)
            // Without the post: never "Tel" or the channel's name (a machine name stays, shown as untitled).
            val alone = parse(name, caption).title
            assertTrue(alone == null || (alone.source == FieldSource.FILE_NAME && !MediaFilenameParser.isTagsOnly(alone.value, otherLanguages = 1)), "$name | $caption -> $alone")
        }
    }

    @Test
    fun theFileKeepsItsOwnTitleAndEpisode() {
        assertEquals("Vikram", parse("[@TR_MOVIES] Vikram (2022) Tamil HDRip 720p.mkv", context = post).title?.value)
        val ep = parse("Tel+Hin E03.mkv", context = "Money Heist Season 2 [Tel + Hin]")
        assertEquals("Money Heist", ep.title?.value)
        assertEquals(2, ep.season?.value)
        assertEquals(3, ep.episode?.value)
    }

    @Test
    fun realTitlesThatLookLikeTagsStay() {
        assertEquals("Ben 10", parse("Ben.10.S01E06.mkv").title?.value)
        assertEquals("Hindi Medium", parse("Hindi.Medium.2017.1080p.mkv").title?.value)
        assertEquals("Us", parse("Us.2019.720p.mkv").title?.value)
        assertEquals("Movie 43", parse("Movie.43.2013.720p.mkv").title?.value)
        assertEquals("Film 0001", parse("Film 0001.1961.1080p.mkv").title?.value)
        assertFalse(MediaFilenameParser.isTagsOnly("Ben"))
        assertTrue(MediaFilenameParser.isTagsOnly("Tel Hin"))
        assertTrue(MediaFilenameParser.isTagsOnly("Tel", fromCaption = true))
        assertTrue(MediaFilenameParser.isTagsOnly("TR Movies"))
        assertTrue(MediaFilenameParser.isTagsOnly("Tamil Dubbed"))
        assertTrue(MediaFilenameParser.isTagsOnly("Tel + Hin 450MB"))
        assertTrue(MediaFilenameParser.isTagsOnly("Tel", otherLanguages = 1))
        assertFalse(MediaFilenameParser.isTagsOnly("Bumblebee 720p"))
    }

    @Test
    fun channelNamesAreNotTitles() {
        assertEquals(FieldSource.FILE_NAME, parse("123.mp4", chat = "TR Movies Tamil").title?.source)
        assertNull(parse("Tel+Hin.mkv", chat = "Dada Linkz").title)
        assertEquals("Doraemon", parse("123.mp4", chat = "Doraemon Tamil Episodes HD").title?.value, "a one-series chat still names it")
    }
}
