package dev.yourstream.playback.torrent

import org.junit.Test
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentPlanTest {
    private val hash = "AABBCCDDEEFF00112233445566778899AABBCCDD"

    @Test
    fun magnetCarriesTheAddonTrackersFirst() {
        val m = TorrentPlan.magnet(TorrentRequest(hash, sources = listOf(
            "tracker:udp://tracker.example.org:1337/announce",
            "dht:aabbccddeeff00112233445566778899aabbccdd",
            "tracker:udp://tracker.example.org:1337/announce",
        ), fileName = "A Film (2020).mkv"))
        assertTrue(m.startsWith("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=A+Film+%282020%29.mkv&tr="))
        val trackers = m.split('&').filter { it.startsWith("tr=") }.map { URLDecoder.decode(it.substring(3), "UTF-8") }
        assertEquals("udp://tracker.example.org:1337/announce", trackers.first(), "the add-on's own trackers come first")
        assertEquals(trackers.distinct(), trackers, "no tracker twice")
        assertTrue(trackers.containsAll(TorrentPlan.PUBLIC_TRACKERS))
        assertFailsWith<IllegalArgumentException> { TorrentPlan.magnet(TorrentRequest("not-a-hash")) }
    }

    @Test
    fun picksTheFileToPlay() {
        val files = listOf(
            TorrentFileEntry(0, "Show/Sample/sample.mkv", 40_000_000),
            TorrentFileEntry(1, "Show/Show.S01E01.mkv", 1_200_000_000),
            TorrentFileEntry(2, "Show/Show.S01E02.mkv", 1_300_000_000),
            TorrentFileEntry(3, "Show/Show.S01E02.en.srt", 60_000),
            TorrentFileEntry(4, "Show/cover.jpg", 2_000_000_000),
        )
        assertEquals(1, TorrentPlan.pickFile(files, 1, null)?.index, "the add-on's fileIdx")
        assertEquals(2, TorrentPlan.pickFile(files, 99, "show.s01e02.mkv")?.index, "a bad index falls back to the file name")
        assertEquals(2, TorrentPlan.pickFile(files, null, null)?.index, "else the largest video, not the largest file")
        assertEquals(4, TorrentPlan.pickFile(listOf(files[4], files[3]), null, null)?.index, "no video at all: the largest file")
        assertNull(TorrentPlan.pickFile(emptyList(), 0, null))
        assertEquals("Show.S01E01.mkv", files[1].name)
        assertEquals("mkv", files[1].extension)
    }

    @Test
    fun readAheadScalesWithTheFile() {
        assertEquals(32L shl 20, TorrentPlan.readAheadBytes(700L shl 20), "small files: at least 32 MB")
        assertEquals((6L shl 30) / 60, TorrentPlan.readAheadBytes(6L shl 30), "about two minutes of a two-hour film")
        assertEquals(256L shl 20, TorrentPlan.readAheadBytes(60L shl 30), "huge files: at most 256 MB")
        assertEquals(100L shl 20, TorrentPlan.spaceNeeded(36L shl 20), "never more than the file plus the margin")
    }

    @Test
    fun pieceMathForAFileInsideATorrent() {
        // Pieces of 1 000 bytes; the file starts 2 500 bytes into the torrent and is 4 000 bytes long:
        // it covers pieces 2 (from 500), 3, 4, 5 and 6 (up to 500).
        val m = PieceMap(fileOffset = 2_500, fileSize = 4_000, pieceLength = 1_000, numPieces = 9)
        assertEquals(2, m.firstPiece)
        assertEquals(6, m.lastPiece)
        assertEquals(2, m.pieceAt(0))
        assertEquals(3, m.pieceAt(500))
        assertEquals(6, m.pieceAt(3_999))
        assertEquals(6, m.pieceAt(10_000), "positions past the end stay in the last piece")
        assertEquals(500, m.pieceEnd(2))
        assertEquals(4_000, m.pieceEnd(6), "the last piece ends with the file")
        assertEquals(3..5, m.window(700, 2_000))
        assertEquals(5..6, m.tail(1_200))

        val have = setOf(3, 4, 6)
        assertEquals(0, m.readableFrom(0, have::contains), "piece 2 is missing")
        assertEquals(2_500 - 700, m.readableFrom(700, have::contains), "pieces 3 and 4: up to 2 500")
        assertEquals(listOf(2 to 0, 5 to 350), m.deadlines(2..6, have::contains, count = 2), "the next missing pieces, in order")
        assertEquals(3, m.count(have::contains))
    }

    @Test
    fun infoHashes() {
        assertTrue(TorrentPlan.isInfoHash(hash))
        assertFalse(TorrentPlan.isInfoHash("aabb"))
        assertFalse(TorrentPlan.isInfoHash(null))
        assertEquals(listOf("udp://a:1", "https://b/announce"), TorrentPlan.trackers(listOf("tracker:udp://a:1", "dht:x", "https://b/announce", "tracker:")))
    }
}
