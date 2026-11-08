package dev.yourstream.media.filename

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfidenceAndRobustnessTest {

    private fun parse(name: String, hints: ParseHints = ParseHints()) = MediaFilenameParser.parse(name, hints)

    @Test
    fun clearNamesScoreHigh() {
        val m = parse("Movie.Name.2024.1080p.WEB-DL.mkv")
        assertTrue(m.title!!.confidence >= 0.9f, "title ${m.title}")
        assertTrue(m.year!!.confidence >= 0.85f, "year ${m.year}")
        assertTrue(m.resolution!!.confidence >= 0.95f)
        assertTrue(m.quality!!.confidence >= 0.9f)
        assertTrue(m.confidence >= 0.85f, "overall ${m.confidence}")
    }

    @Test
    fun parenthesisedYearIsStrongest() {
        assertEquals(0.95f, parse("Leo (2023) Tamil.mkv").year!!.confidence)
        assertTrue(parse("Leo 2023.mkv").year!!.confidence < 0.95f)
        assertTrue(parse("Blade.Runner.2049.2017.mkv").year!!.confidence <= 0.8f, "two year-like numbers → less sure")
    }

    @Test
    fun episodeStylesHaveGradedConfidence() {
        val sxe = parse("Show.S01E05.mkv").episode!!.confidence
        val nxn = parse("Show.1x05.mkv").episode!!.confidence
        val bare = parse("Show.E05.mkv").episode!!.confidence
        val absolute = parse("Shinchan.125.Tamil.mp4").absoluteEpisode!!.confidence
        val unsure = parse("Ben.10.mkv").absoluteEpisode!!.confidence
        assertTrue(sxe > nxn && nxn > bare && bare > absolute && absolute > unsure, "$sxe $nxn $bare $absolute $unsure")
        assertTrue(parse("Ben.10.mkv").warnings.any { "may be part of the title" in it })
    }

    @Test
    fun languageConfidenceDependsOnEvidence() {
        val full = parse("Movie.2020.Tamil.mkv").languages.single().confidence
        val native = parse("Movie 2020 தமிழ்.mkv").languages.single().confidence
        val abbreviated = parse("Movie.2020.Tam+Tel.mkv").languages.first().confidence
        assertEquals(0.85f, full)
        assertEquals(0.9f, native)
        assertEquals(0.75f, abbreviated)
    }

    @Test
    fun fieldConfidencesAreExposedForStorage() {
        val map = parse("Doraemon.S03E12.Tamil.720p.mkv").fieldConfidences()
        assertTrue(listOf("title", "season", "episode", "language", "resolution", "kind").all { it in map }, "$map")
        assertTrue(map.values.all { it in 0f..1f })
    }

    @Test
    fun captionAndChatTitleFallbacksAreMarkedAndDiscounted() {
        val fromCaption = parse("video_2024.mp4", ParseHints(caption = "Maharaja (2024) Tamil"))
        assertEquals(FieldSource.CAPTION, fromCaption.title!!.source)
        assertTrue(fromCaption.warnings.any { "caption" in it })
        val fromChat = parse("123.mp4", ParseHints(chatTitle = "Doraemon Tamil Episodes HD"))
        assertEquals("Doraemon", fromChat.title!!.value)
        assertEquals(FieldSource.CHAT_TITLE, fromChat.title!!.source)
        assertTrue(fromChat.title!!.confidence < 0.5f)
    }

    @Test
    fun theOriginalNameIsNeverModified() {
        val names = listOf("Movie.Name.2024.1080p.WEB-DL.mkv", "  spaced name .mkv ", "", "🎬 x", "a\u0000b.mkv")
        names.forEach { assertEquals(it, parse(it).original) }
    }

    @Test
    fun garbageNeverThrowsAndStaysInRange() {
        val random = Random(42)
        val alphabet = "abcXYZ019 ._-[](){}+&@#!?/\\:;'\"தமிழ்हिंदी🎬\u0000\n\t" + "S01E05x264.1080p"
        repeat(3_000) {
            val name = (0 until random.nextInt(0, 120)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val m = parse(name, ParseHints(caption = name.reversed(), chatTitle = name.take(10)))
            assertTrue(m.confidence in 0f..1f)
            assertTrue(m.fieldConfidences().values.all { it in 0f..1f })
            assertTrue(m.warnings.none { it.startsWith("parser error") }, "crashed on '$name': ${m.warnings}")
            assertEquals(name, m.original)
        }
    }

    @Test
    fun hugeInputIsBounded() {
        val name = "Movie ".repeat(10_000) + "2020.mkv"
        val m = parse(name)
        assertEquals(name, m.original)
        assertTrue(m.cleanedName.length <= 600)
    }

    @Test
    fun emptyAndSymbolOnlyInputs() {
        assertNull(parse("").title)
        assertNull(parse("....").title)
        assertNull(parse("[][]().mkv").title)
        assertEquals(MediaKindHint.UNKNOWN, parse("").kind.value)
    }

    @Test
    fun titleCasingIsTidiedButNotForced() {
        assertEquals("Doraemon", parse("doraemon.s01e01.mkv").title!!.value)
        assertEquals("Harry Potter and the Chamber of Secrets", parse("HARRY.POTTER.AND.THE.CHAMBER.OF.SECRETS.2002.mkv").title!!.value)
        assertEquals("iCarly", parse("iCarly.S01E01.mkv").title!!.value)
        assertEquals("RRR", parse("RRR.2022.mkv").title!!.value)
        assertEquals("Rocky II", parse("ROCKY II 1979.mkv").title!!.value)
    }

    @Test
    fun normalisedTitleIsAMatchingKey() {
        assertEquals("tom and jerry", parse("Tom & Jerry 1995.mkv").normalizedTitle)
        assertEquals("tom and jerry", parse("tom.and.jerry.1995.mkv").normalizedTitle)
        assertEquals("pokemon", parse("Pokémon.S01E01.mkv").normalizedTitle)
    }

    @Test
    fun subtitleFilesDescribeTheirContent() {
        val m = parse("Movie.Name.2020.English.SDH.forced.srt")
        val subs = assertNotNull(m.subtitles)
        assertTrue(subs.isSubtitleFile)
        assertTrue(subs.forced)
        assertTrue(subs.sdh)
        assertEquals(listOf("en"), subs.languages.map { it.code })
    }

    @Test
    fun captionTextCanBeParsedDirectly() {
        val m = MediaFilenameParser.parseText("Doraemon Season 2 Episode 14 (Tamil) 720p")
        assertEquals("Doraemon", m.title!!.value)
        assertEquals(2, m.season!!.value)
        assertEquals(14, m.episode!!.value)
        assertNull(m.extension)
    }
}
