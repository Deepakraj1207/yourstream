package dev.yourstream.playback.torrent

import java.net.URLEncoder

/** What to stream: one file of a torrent, as a stream add-on described it. */
data class TorrentRequest(
    /** 40 hex digits (BitTorrent v1 info hash). */
    val infoHash: String,
    /** Which file of the torrent (null: the add-on's file name, else the largest video). */
    val fileIdx: Int? = null,
    /** The add-on's peer sources: "tracker:udp://…", "dht:…" (and "peer:host:port" for a known peer). */
    val sources: List<String> = emptyList(),
    val fileName: String? = null,
) {
    val key: String get() = infoHash.lowercase()
}

/** One file inside a torrent. */
data class TorrentFileEntry(val index: Int, val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Decisions about a torrent stream that need no network (pure; tested). */
object TorrentPlan {
    private val HASH = Regex("(?i)[0-9a-f]{40}")

    val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv", "vob")

    /**
     * Widely used public trackers, added to every torrent like Stremio's streaming server does:
     * peers are found faster than with DHT alone.
     */
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
    )

    fun isInfoHash(text: String?): Boolean = text != null && HASH.matches(text)

    /** Trackers from an add-on's sources ("tracker:udp://…"); "dht:…" needs none (DHT is on). */
    fun trackers(sources: List<String>): List<String> = sources.mapNotNull { s ->
        when {
            s.startsWith("tracker:", ignoreCase = true) -> s.substring(8).trim()
            s.startsWith("udp://") || s.startsWith("http://") || s.startsWith("https://") -> s.trim()
            else -> null
        }
    }.filter { it.isNotEmpty() }.distinct()

    /** Known peers from sources ("peer:192.168.1.5:6881", e.g. a seed on the local network). */
    fun peers(sources: List<String>): List<String> = sources.mapNotNull { s ->
        s.takeIf { it.startsWith("peer:", ignoreCase = true) }?.substring(5)?.trim()?.takeIf { PEER.matches(it) }
    }.distinct()

    /** host:port or [v6]:port; written into the magnet as is (libtorrent does not unescape x.pe). */
    private val PEER = Regex("""(\[[0-9a-fA-F:.]+]|[0-9A-Za-z.-]+):\d{1,5}""")

    /** The magnet link for [request]: its hash, the add-on's trackers first, then [PUBLIC_TRACKERS], and known peers. */
    fun magnet(request: TorrentRequest): String {
        require(isInfoHash(request.infoHash)) { "not an info hash: ${request.infoHash}" }
        val trackers = (trackers(request.sources) + PUBLIC_TRACKERS).distinct()
        return buildString {
            append("magnet:?xt=urn:btih:").append(request.key)
            request.fileName?.let { append("&dn=").append(enc(it)) }
            trackers.forEach { append("&tr=").append(enc(it)) }
            peers(request.sources).forEach { append("&x.pe=").append(it) }
        }
    }

    /**
     * The file to play: the add-on's [fileIdx] when it names a real file; else the file called
     * [fileName]; else the largest video; else the largest file. Null for an empty torrent.
     */
    fun pickFile(files: List<TorrentFileEntry>, fileIdx: Int?, fileName: String?): TorrentFileEntry? {
        if (files.isEmpty()) return null
        fileIdx?.let { i -> files.firstOrNull { it.index == i && it.size > 0 }?.let { return it } }
        fileName?.takeIf { it.isNotBlank() }?.let { n -> files.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it } }
        return files.filter { it.extension in VIDEO_EXTENSIONS }.maxByOrNull { it.size } ?: files.maxByOrNull { it.size }
    }

    /**
     * How far ahead of the player to keep downloading: about two minutes of a two-hour film
     * (size / 60), at least 32 MB and at most 256 MB. Bandwidth is not spent on parts of the file
     * nobody watches.
     */
    fun readAheadBytes(fileSize: Long): Long = (fileSize / 60).coerceIn(32L shl 20, 256L shl 20)

    /** Free space a stream needs before it starts: the read-ahead twice over plus a margin, never more than the file. */
    fun spaceNeeded(fileSize: Long): Long = minOf(fileSize, readAheadBytes(fileSize) * 2) + (64L shl 20)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * Piece arithmetic for one file of a torrent: which pieces hold which bytes. A torrent is one
 * byte sequence cut into equal pieces; files sit in it back to back, so a file starts and ends
 * partway into pieces it may share with its neighbours.
 */
data class PieceMap(
    /** Where the file starts in the torrent's byte sequence. */
    val fileOffset: Long,
    val fileSize: Long,
    val pieceLength: Int,
    val numPieces: Int,
) {
    init { require(pieceLength > 0 && fileSize > 0 && numPieces > 0) }

    val firstPiece: Int get() = pieceAt(0)
    val lastPiece: Int get() = pieceAt(fileSize - 1)

    /** The piece holding byte [position] of the file. */
    fun pieceAt(position: Long): Int = ((fileOffset + position.coerceIn(0, fileSize - 1)) / pieceLength).toInt().coerceIn(0, numPieces - 1)

    /** Where piece [p] ends, as a file position (exclusive), capped to the file's end. */
    fun pieceEnd(p: Int): Long = ((p + 1).toLong() * pieceLength - fileOffset).coerceIn(0, fileSize)

    /** The pieces to fetch when the player reads at [position]: its piece and those covering [aheadBytes] after it. */
    fun window(position: Long, aheadBytes: Long): IntRange = pieceAt(position)..pieceAt(position + aheadBytes)

    /**
     * The end of the file, where containers often keep their index (MKV cues, an MP4 moov box
     * that was not moved to the front): fetched early so preparing and seeking do not stall.
     */
    fun tail(bytes: Long = 4L shl 20): IntRange = pieceAt(fileSize - bytes)..lastPiece

    /**
     * Deadlines (ms from now) for the next [count] missing pieces of [window], staggered so
     * libtorrent fetches them in order: the piece the player waits for first.
     */
    fun deadlines(window: IntRange, have: (Int) -> Boolean, count: Int, stepMs: Int = 350): List<Pair<Int, Int>> =
        window.asSequence().filter { !have(it) }.take(count).mapIndexed { i, p -> p to i * stepMs }.toList()

    /** How many bytes from [position] can be read now: up to the end of the run of pieces present from there. */
    fun readableFrom(position: Long, have: (Int) -> Boolean, maxPieces: Int = 64): Long {
        val first = pieceAt(position)
        if (!have(first)) return 0
        var end = first
        while (end < lastPiece && end - first < maxPieces && have(end + 1)) end++
        return pieceEnd(end) - position
    }

    /** How many of the file's pieces are present, given [have]. */
    fun count(have: (Int) -> Boolean): Int = (firstPiece..lastPiece).count(have)
}
