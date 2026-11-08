package dev.yourstream.media.filename

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Table-driven corpus. Each case states only what it cares about; a field not mentioned is not
 * checked. Every failure is reported at once with the full parse.
 */
class CorpusTest {

    private class Expect(val name: String, val hints: ParseHints) {
        val checks = mutableListOf<Pair<String, (FilenameMetadata) -> Any?>>()
        val expected = mutableListOf<Any?>()
        private fun check(label: String, value: Any?, get: (FilenameMetadata) -> Any?) { checks += label to get; expected += value }
        fun title(v: String?) = check("title", v) { it.title?.value }
        fun year(v: Int?) = check("year", v) { it.year?.value }
        fun season(v: Int?) = check("season", v) { it.season?.value }
        fun seasonEnd(v: Int?) = check("seasonEnd", v) { it.seasonEnd }
        fun episode(v: Int?) = check("episode", v) { it.episode?.value }
        fun episodeEnd(v: Int?) = check("episodeEnd", v) { it.episodeEnd }
        fun absolute(v: Int?) = check("absoluteEpisode", v) { it.absoluteEpisode?.value }
        fun airDate(v: String?) = check("airDate", v) { it.airDate?.value }
        fun res(v: Int?) = check("resolution", v) { it.resolution?.value }
        fun quality(v: String?) = check("quality", v) { it.quality?.value }
        fun codec(v: String?) = check("videoCodec", v) { it.videoCodec?.value }
        fun langs(vararg codes: String) = check("languages", codes.toList()) { m -> m.languages.map { it.value.code } }
        fun audio(vararg codecs: String) = check("audio", codecs.toList()) { it.audio?.codecs ?: emptyList<String>() }
        fun channels(v: String?) = check("channels", v) { it.audio?.channels }
        fun dual(v: Boolean) = check("dualAudio", v) { it.audio?.isDualAudio == true }
        fun subs(vararg codes: String) = check("subtitles", codes.toList()) { m -> m.subtitles?.languages?.map { it.code } ?: emptyList<String>() }
        fun hardsub(v: Boolean?) = check("hardcoded", v) { it.subtitles?.hardcoded }
        fun group(v: String?) = check("group", v) { it.releaseGroup?.value }
        fun part(v: Int?) = check("part", v) { it.partNumber?.value }
        fun ext(v: String?) = check("ext", v) { it.extension }
        fun hdr(v: String?) = check("hdr", v) { it.hdr?.value }
        fun service(v: String?) = check("service", v) { it.streamingService?.value }
        fun edition(v: String?) = check("edition", v) { it.edition?.value }
        fun kind(v: MediaKindHint) = check("kind", v) { it.kind.value }
        fun bits(v: Int?) = check("bitDepth", v) { it.bitDepth }
        fun dubbed(v: Boolean) = check("dubbed", v) { it.isDubbed }
        fun alt(v: String?) = check("altTitle", v) { it.alternativeTitle?.value }
        fun normalized(v: String?) = check("normalizedTitle", v) { it.normalizedTitle }
        fun flag(f: ReleaseFlag) = check("flag $f", true) { f in it.flags }
    }

    private val cases = mutableListOf<Expect>()
    private fun case(name: String, hints: ParseHints = ParseHints(), block: Expect.() -> Unit) {
        cases += Expect(name, hints).apply(block)
    }

    init {
        // ---------------- the examples from the brief ----------------
        case("Movie.Name.2024.1080p.WEB-DL.mkv") { title("Movie Name"); year(2024); res(1080); quality("WEB-DL"); ext("mkv"); kind(MediaKindHint.MOVIE) }
        case("Doraemon.S01E05.Tamil.720p.mkv") { title("Doraemon"); season(1); episode(5); langs("ta"); res(720); kind(MediaKindHint.EPISODE) }
        case("Doraemon.S03E12.Tamil.720p") { title("Doraemon"); season(3); episode(12); langs("ta"); res(720); ext(null) }
        case("Shinchan.125.Tamil.mp4") { title("Shinchan"); absolute(125); season(null); episode(null); langs("ta"); kind(MediaKindHint.EPISODE) }
        case("Ben.10.S02E03.English.1080p.mkv") { title("Ben 10"); season(2); episode(3); langs("en"); res(1080) }
        case("Tom.and.Jerry.1995.720p.Hindi.mkv") { title("Tom and Jerry"); year(1995); res(720); langs("hi") }

        // ---------------- movies ----------------
        case("Vikram.2022.1080p.WEB-DL.Tamil.DDP5.1.x264.ESub.mkv") {
            title("Vikram"); year(2022); res(1080); quality("WEB-DL"); langs("ta"); audio("DD+"); channels("5.1"); codec("H.264"); subs("en")
        }
        case("www.1TamilMV.xyz - Leo (2023) Tamil HQ HDRip - 720p - x264 - (DD+5.1 - 192Kbps & AAC) - 1.4GB - ESub.mkv") {
            title("Leo"); year(2023); quality("HDRip"); res(720); codec("H.264"); audio("DD+", "AAC"); langs("ta"); subs("en"); absolute(null)
        }
        case("1917.2019.1080p.BluRay.x264.mkv") { title("1917"); year(2019); quality("BluRay") }
        case("1917.mkv") { title("1917"); year(null) }
        case("2012.2009.720p.BluRay.mkv") { title("2012"); year(2009) }
        case("Blade.Runner.2049.2017.2160p.UHD.BluRay.HEVC.HDR10.mkv") { title("Blade Runner 2049"); year(2017); res(2160); codec("H.265"); hdr("HDR10") }
        case("2001.A.Space.Odyssey.1968.REMASTERED.1080p.BluRay.mkv") { title("2001 A Space Odyssey"); year(1968); edition("Remastered") }
        case("The.Matrix.1999.1080p.BluRay.x265.10bit.AAC.5.1-RARBG.mp4") { title("The Matrix"); year(1999); codec("H.265"); bits(10); audio("AAC"); channels("5.1"); group("RARBG") }
        case("Inception (2010) [1080p] [BluRay] [5.1] [YTS.MX].mp4") { title("Inception"); year(2010); res(1080); quality("BluRay"); channels("5.1") }
        case("Avatar.The.Way.of.Water.2022.1080p.mkv.001") { title("Avatar The Way of Water"); year(2022); part(1); ext("mkv") }
        case("Movie.Name.2021.CD2.avi") { title("Movie Name"); year(2021); part(2) }
        case("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv") {
            title("Dune Part Two"); year(2024); part(null); quality("WEB-DL"); audio("DD+", "Atmos"); hdr("DV HDR"); codec("H.265"); group("FLUX")
        }
        case("Oppenheimer.2023.IMAX.1080p.mkv") { title("Oppenheimer"); year(2023); edition("IMAX") }
        case("Spider-Man.No.Way.Home.2021.Extended.1080p.mkv") { title("Spider-Man No Way Home"); year(2021); edition("Extended") }
        case("Amélie.2001.FRENCH.720p.BluRay.mkv") { title("Amélie"); year(2001); langs("fr"); normalized("amelie") }
        case("Ocean's.Eleven.2001.1080p.mkv") { title("Ocean's Eleven"); normalized("oceans eleven"); year(2001) }
        case("RRR (2022) [Tam + Tel + Hin] 2160p 4K HDR.mkv") { title("RRR"); year(2022); res(2160); langs("ta", "te", "hi") }
        case("KGF Chapter 2 (2022) Kannada 1080p HDRip.mkv") { title("KGF Chapter 2"); year(2022); langs("kn"); quality("HDRip") }
        case("Manjummel Boys (2024) Malayalam PreDVD 480p.mkv") { title("Manjummel Boys"); year(2024); quality("PreDVD"); res(480); langs("ml") }
        case("@CinemaChannel Jailer 2023 Tamil 1080p.mp4") { title("Jailer"); year(2023); langs("ta") }
        case("English Vinglish (2012) Hindi 720p.mkv") { title("English Vinglish"); year(2012); langs("hi") }
        case("The.English.Patient.1996.720p.mkv") { title("The English Patient"); year(1996); langs() }
        case("Leo Tamil 2023 1080p.mkv") { title("Leo"); year(2023); langs("ta") }
        case("Kantara.2022.Kannada.Dual.Audio.WEBRip.mkv") { title("Kantara"); quality("WEBRip"); dual(true); langs("kn") }
        case("Pushpa 2 The Rule (2024) Hindi Dubbed 1080p.mkv") { title("Pushpa 2 The Rule"); year(2024); langs("hi"); dubbed(true) }
        case("Salaar.2023.Tam-Tel-Hin-Mal-Kan.1080p.mkv") { title("Salaar"); langs("ta", "te", "hi", "ml", "kn") }
        case("Jawan (2023) हिंदी 720p.mkv") { title("Jawan"); year(2023); langs("hi") }
        case("Vikram (2022) தமிழ் HDRip.mkv") { title("Vikram"); langs("ta"); quality("HDRip") }
        case("Parasite.2019.KOREAN.1080p.BluRay.x264.mkv") { title("Parasite"); langs("ko") }
        case("Movie.Name.2020.NF.WEB-DL.1080p.mkv") { title("Movie Name"); service("Netflix"); quality("WEB-DL") }
        case("Movie.Name.2020.AMZN.WEBRip.mkv") { title("Movie Name"); service("Amazon"); quality("WEBRip") }
        case("Movie.Name.2020.Hotstar.WEB-DL.mkv") { service("Disney+ Hotstar") }
        case("Movie Name 2019 HDCAM.mp4") { title("Movie Name"); quality("HDCAM") }
        case("Movie-Name-2019-720p-WEBRip.mp4") { title("Movie Name"); year(2019); res(720); quality("WEBRip"); group(null) }
        case("Movie_Name_2018_1080p.mkv") { title("Movie Name"); year(2018); res(1080) }
        case("Movie Name (2020) 1920x1080.mkv") { title("Movie Name"); res(1080) }
        case("Movie.Name.2020.PROPER.REPACK.1080p.mkv") { title("Movie Name"); flag(ReleaseFlag.PROPER); flag(ReleaseFlag.REPACK) }
        case("Avengers.Endgame.2019.3D.HSBS.1080p.mkv") { title("Avengers Endgame"); flag(ReleaseFlag.THREE_D) }
        case("Movie.Name.2020.Trailer.mp4") { title("Movie Name"); flag(ReleaseFlag.TRAILER) }
        case("Movie.Name.2020.HC.HDRip.mkv") { hardsub(true); quality("HDRip") }
        case("Movie Name 2020 Eng Subs 720p.mkv") { title("Movie Name"); subs("en"); langs() }
        case("Movie.Name.2020.MSubs.mkv") { subs("mul") }
        case("Movie.Name.2020.Arabic.Subbed.mkv") { subs("ar"); langs() }
        case("Kung Fu Panda aka Panda Kung Fu 2008 720p.mkv") { title("Kung Fu Panda"); alt("Panda Kung Fu"); year(2008) }
        case("MOVIE NAME 2020 1080P.mkv") { title("Movie Name"); year(2020); res(1080) }
        case("the lord of the rings the return of the king 2003.mkv") { title("The Lord of the Rings the Return of the King"); year(2003) }
        case("Movie Name 2020 Full Movie HD.mp4") { title("Movie Name"); year(2020) }
        case("Movie.Name.2020.10bit.HEVC.mkv") { bits(10); codec("H.265") }
        case("Movie.Name.2020.DTS-HD.MA.7.1.mkv") { audio("DTS-HD MA"); channels("7.1") }
        case("Movie.Name.2020.TrueHD.Atmos.7.1.mkv") { audio("TrueHD", "Atmos") }
        case("Movie.Name.2020.AAC2.0.mkv") { audio("AAC"); channels("2.0") }
        case("Movie.Name.2020.320kbps.mp3") { ext("mp3") }

        // ---------------- episodes ----------------
        case("The.Office.US.S02E05.720p.BluRay.x265-GROUP.mkv") { title("The Office US"); season(2); episode(5); codec("H.265"); group("GROUP") }
        case("Breaking Bad 3x07 HDTV.avi") { title("Breaking Bad"); season(3); episode(7); quality("HDTV") }
        case("Panchayat Season 2 Episode 4 Hindi 1080p.mp4") { title("Panchayat"); season(2); episode(4); langs("hi") }
        case("Stranger.Things.S04E01-E02.1080p.mkv") { title("Stranger Things"); season(4); episode(1); episodeEnd(2) }
        case("Show.S01E01E02E03.mkv") { season(1); episode(1); episodeEnd(3) }
        case("Show.S01E01-03.720p.mkv") { episode(1); episodeEnd(3); res(720) }
        case("show.s1e5.mkv") { title("Show"); season(1); episode(5) }
        case("Show S01 E05 720p.mkv") { title("Show"); season(1); episode(5) }
        case("Show.S01.E05.mkv") { title("Show"); season(1); episode(5) }
        case("Show S1 EP5.mkv") { title("Show"); season(1); episode(5) }
        case("Mahabharat Episode 45.mp4") { title("Mahabharat"); episode(45); season(null) }
        case("Mahabharat Ep 45 Hindi.mp4") { title("Mahabharat"); episode(45); langs("hi") }
        case("Chota Bheem EP125 Tamil.mp4") { title("Chota Bheem"); episode(125); langs("ta") }
        case("Pokemon E05 Tamil.mp4") { title("Pokemon"); episode(5) }
        case("Friends.Season.1.Episode.1.mkv") { title("Friends"); season(1); episode(1) }
        case("Friends S01-S10 Complete 1080p.mkv") { title("Friends"); season(1); seasonEnd(10); episode(null); kind(MediaKindHint.SEASON_PACK); flag(ReleaseFlag.COMPLETE) }
        case("Dark.S02.1080p.NF.WEB-DL.mkv") { title("Dark"); season(2); episode(null); kind(MediaKindHint.SEASON_PACK) }
        case("Money Heist Season 3 Complete.mkv") { title("Money Heist"); season(3); kind(MediaKindHint.SEASON_PACK) }
        case("Episodes 1-10.mkv") { episode(1); episodeEnd(10) }
        case("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv") { title("Frieren"); absolute(12); res(1080); group("SubsPlease") }
        case("[Erai-raws] One Piece - 1085 [720p][Multiple Subtitle].mkv") { title("One Piece"); absolute(1085); res(720); group("Erai-raws") }
        case("Naruto Shippuden 345 Hindi 720p.mp4") { title("Naruto Shippuden"); absolute(345); langs("hi") }
        case("Doraemon 1505 Tamil.mp4") { title("Doraemon"); absolute(1505) }
        case("Doraemon Tamil Episode 23.mp4") { title("Doraemon"); episode(23); langs("ta") }
        case("Shinchan.Tamil.125.mp4") { title("Shinchan"); langs("ta"); absolute(125) }
        case("The.Daily.Show.2024.03.15.720p.WEB.mkv") { title("The Daily Show"); airDate("2024-03-15"); year(null); kind(MediaKindHint.EPISODE) }
        case("Show.2019.S01E03.1080p.mkv") { title("Show"); year(2019); season(1); episode(3) }
        case("Ben.10.Alien.Force.S01E01.mkv") { title("Ben 10 Alien Force"); season(1); episode(1) }
        case("Game of Thrones S08E06 The Iron Throne 1080p.mkv") { title("Game of Thrones"); season(8); episode(6) }
        case("S01E05 Doraemon Tamil.mkv") { title("Doraemon"); season(1); episode(5) }

        // ---------------- subtitles & audio files ----------------
        case("Interstellar.srt") { title("Interstellar"); ext("srt") }
        case("Movie.Name.2020.en.srt") { title("Movie Name"); subs("en"); ext("srt") }
        case("Movie.Name.2020.English.forced.srt") { subs("en") }
        case("Movie.Name.ta.srt") { title("Movie Name"); subs("ta") }
        case("Arijit Singh - Tum Hi Ho.mp3") { ext("mp3"); title("Arijit Singh - Tum Hi Ho") }

        // ---------------- noisy / generic / weird ----------------
        case("🎬 Leo 2023 Tamil 1080p 🔥.mkv") { title("Leo"); year(2023); langs("ta") }
        case("[TamilBlasters.cc] Vikram 2022 Tamil.mkv") { title("Vikram"); year(2022) }
        case("Vikram 2022 Tamil t.me/somechannel.mkv") { title("Vikram"); year(2022) }
        case("video_2024-03-01_12-00-00.mp4", ParseHints(caption = "🎬 Maharaja (2024) Tamil 1080p\n\nJoin @somechannel")) {
            title("Maharaja"); year(2024); res(1080); langs("ta")
        }
        case("VID_20240301_WA0003.mp4", ParseHints(chatTitle = "Doraemon Tamil Episodes")) { title("Doraemon") }
        case("1.mkv", ParseHints(caption = "#doraemon\nDoraemon Episode 12 Tamil")) { title("Doraemon"); episode(12) }
        case("Dune.Part.Two.2024.2160p.mkv", ParseHints(caption = "Watch now!")) { title("Dune Part Two") }
        case("[1080p] Movie Name 2020.mkv") { title("Movie Name"); res(1080); year(2020) }
        case("Movie.Name.mkv") { title("Movie Name"); year(null); kind(MediaKindHint.UNKNOWN) }
        case("movie") { title("Movie"); ext(null) }
    }

    @Test
    fun corpus() {
        val failures = cases.mapNotNull { c ->
            val m = MediaFilenameParser.parse(c.name, c.hints)
            val problems = c.checks.zip(c.expected).mapNotNull { (check, expected) ->
                val actual = check.second(m)
                if (actual == expected) null else "${check.first}: expected <$expected> got <$actual>"
            }
            if (problems.isEmpty()) null else "${c.name}\n    ${problems.joinToString("\n    ")}\n    (cleaned='${m.cleanedName}', warnings=${m.warnings})"
        }
        assertTrue(failures.isEmpty(), "${failures.size}/${cases.size} cases failed:\n" + failures.joinToString("\n"))
    }

    @Test
    fun corpusIsLarge() {
        assertTrue(cases.size >= 100, "corpus has ${cases.size} cases")
    }
}
