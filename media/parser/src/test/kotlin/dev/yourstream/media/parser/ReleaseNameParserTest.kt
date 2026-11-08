package dev.yourstream.media.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNameParserTest {

    private data class Case(
        val name: String,
        val title: String?,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val resolution: Int? = null,
        val source: String? = null,
        val codec: String? = null,
        val languages: List<String>? = null,
        val subs: List<String>? = null,
        val part: Int? = null,
        val group: String? = null,
    )

    private val corpus = listOf(
        Case("Vikram.2022.1080p.WEB-DL.Tamil.DDP5.1.x264.ESub.mkv", "Vikram", 2022, resolution = 1080, source = "WEB-DL", codec = "H.264", languages = listOf("tamil"), subs = listOf("english")),
        Case(
            "www.1TamilMV.xyz - Leo (2023) Tamil HQ HDRip - 720p - x264 - (DD+5.1 - 192Kbps & AAC) - 1.4GB - ESub.mkv",
            "Leo", 2023, resolution = 720, source = "HDRip", codec = "H.264", languages = listOf("tamil"), subs = listOf("english"),
        ),
        Case("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv", "Frieren", episode = 12, resolution = 1080, group = "SubsPlease"),
        Case("The.Office.US.S02E05.720p.BluRay.x265-GROUP.mkv", "The Office US", season = 2, episode = 5, resolution = 720, source = "BluRay", codec = "H.265", group = "GROUP"),
        Case("Breaking Bad 3x07 HDTV.avi", "Breaking Bad", season = 3, episode = 7, source = "HDTV"),
        Case("Panchayat Season 2 Episode 4 Hindi 1080p.mp4", "Panchayat", season = 2, episode = 4, resolution = 1080, languages = listOf("hindi")),
        Case("RRR (2022) [Tam + Tel + Hin] 2160p 4K HDR.mkv", "RRR", 2022, resolution = 2160, languages = listOf("tamil", "telugu", "hindi")),
        Case("1917.2019.1080p.BluRay.x264.mkv", "1917", 2019, resolution = 1080, source = "BluRay"),
        Case("Blade.Runner.2049.2017.2160p.UHD.BluRay.HEVC.mkv", "Blade Runner 2049", 2017, resolution = 2160, codec = "H.265"),
        Case("Avatar.The.Way.of.Water.2022.1080p.mkv.001", "Avatar The Way of Water", 2022, resolution = 1080, part = 1),
        Case("Manjummel Boys (2024) Malayalam PreDVD 480p.mkv", "Manjummel Boys", 2024, resolution = 480, source = "PreDVD", languages = listOf("malayalam")),
        Case("@CinemaChannel Jailer 2023 Tamil 1080p.mp4", "Jailer", 2023, resolution = 1080, languages = listOf("tamil")),
        Case("English Vinglish (2012) Hindi 720p.mkv", "English Vinglish", 2012, resolution = 720, languages = listOf("hindi")),
        Case("Kantara.2022.Kannada.Dual.Audio.WEBRip.mkv", "Kantara", 2022, source = "WEBRip"),
        Case("Stranger.Things.S04E01-E02.1080p.mkv", "Stranger Things", season = 4, episode = 1, resolution = 1080),
        Case("Movie.Name.2021.CD2.avi", "Movie Name", 2021, part = 2),
        Case("Interstellar.srt", "Interstellar"),
    )

    @Test
    fun corpus() {
        val failures = corpus.mapNotNull { case ->
            val p = ReleaseNameParser.parse(case.name)
            val problems = buildList {
                if (p.title != case.title) add("title=${p.title}")
                if (p.year != case.year) add("year=${p.year}")
                if (p.season != case.season) add("season=${p.season}")
                if (p.episode != case.episode) add("episode=${p.episode}")
                if (p.resolution != case.resolution) add("resolution=${p.resolution}")
                if (case.source != null && p.source != case.source) add("source=${p.source}")
                if (case.codec != null && p.videoCodec != case.codec) add("codec=${p.videoCodec}")
                if (case.languages != null && !p.languages.containsAll(case.languages)) add("languages=${p.languages}")
                if (case.subs != null && p.subtitleLanguages != case.subs) add("subs=${p.subtitleLanguages}")
                if (p.partNumber != case.part) add("part=${p.partNumber}")
                if (case.group != null && p.releaseGroup != case.group) add("group=${p.releaseGroup}")
            }
            if (problems.isEmpty()) null else "${case.name}\n    -> $problems"
        }
        assertTrue(failures.isEmpty(), "Parser mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun episodeRangesAndMultiAudio() {
        val range = ReleaseNameParser.parse("Show.S01E01-E03.mkv")
        assertEquals(3, range.episodeEnd)
        assertTrue("multi" in ReleaseNameParser.parse("Kantara.2022.Dual.Audio.mkv").languages)
    }

    @Test
    fun genericFileNameFallsBackToCaption() {
        val parsed = ReleaseNameParser.parse(
            "video_2024-03-01_12-00-00.mp4",
            caption = "🎬 Maharaja (2024) Tamil 1080p\n\nJoin @somechannel",
        )
        assertEquals("Maharaja", parsed.title)
        assertEquals(2024, parsed.year)
        assertEquals(1080, parsed.resolution)
    }

    @Test
    fun goodFileNameIgnoresCaption() {
        val parsed = ReleaseNameParser.parse("Dune.Part.Two.2024.2160p.mkv", caption = "Watch now!")
        assertEquals("Dune Part Two", parsed.title)
        assertNull(parsed.partNumber)
    }

    @Test
    fun confidenceReflectsUnderstanding() {
        assertTrue(ReleaseNameParser.parse("Vikram.2022.1080p.WEB-DL.mkv").confidence >= 0.9f)
        assertTrue(ReleaseNameParser.parse("abc.bin").confidence < 0.6f)
    }
}
