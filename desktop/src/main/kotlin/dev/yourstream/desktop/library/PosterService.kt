package dev.yourstream.desktop.library

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.core.match.MatchDecision
import dev.yourstream.metadata.core.match.MatchInput
import dev.yourstream.metadata.core.match.MetadataMatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest

/** What the poster lookup found for a title ("" = looked, nothing confident). */
@Serializable
data class Artwork(val poster: String = "", val backdrop: String = "", val overview: String = "", val rating: Double? = null)

/**
 * Posters for library titles, matched with the Android app's matcher (TMDB with a key, Cinemeta
 * without), and the images themselves, cached on disk. Only the title and year go to the
 * metadata service, never file names or chat names.
 */
class PosterService(
    private val matcher: MetadataMatcher,
    private val http: HttpClient,
    private val dir: File,
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(dir, "matches.json")
    private val lock = Mutex()
    private val matches: MutableMap<String, Artwork> = load()
    private val lookups = Semaphore(3)
    private val downloads = Semaphore(6)
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }

    private fun load(): MutableMap<String, Artwork> = try {
        if (index.isFile) json.decodeFromString<Map<String, Artwork>>(index.readText()).toMutableMap() else HashMap()
    } catch (_: Exception) { HashMap() }

    fun cached(title: LibraryTitle): Artwork? = synchronized(matches) { matches[title.key] }

    suspend fun artwork(title: LibraryTitle): Artwork {
        cached(title)?.let { return it }
        return lookups.withPermit {
            cached(title)?.let { return@withPermit it }
            val type = if (title.isSeries) MetadataType.SERIES else MetadataType.MOVIE
            val outcome = matcher.match(MatchInput(listOf(title.title), title.year, type, title.files.flatMap { it.languages }.distinct()))
            val best = outcome.best?.candidate
            val art = if (outcome.decision == MatchDecision.ACCEPTED && best != null) {
                Artwork(best.posterUrl.orEmpty(), best.backdropUrl.orEmpty(), best.overview.orEmpty(), best.rating)
            } else Artwork()
            // A failed lookup (offline) is not remembered, so it is tried again next time.
            if (outcome.error == null || art.poster.isNotEmpty()) save(title.key, art)
            art
        }
    }

    private suspend fun save(key: String, art: Artwork) = lock.withLock {
        synchronized(matches) { matches[key] = art }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val snapshot = synchronized(matches) { HashMap(matches) }
            val tmp = File(dir, "matches.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        }
    }

    /** The image at [url], from memory, the disk cache or the network; null when it cannot be had. */
    suspend fun image(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        synchronized(memory) { memory[url] }?.let { return it }
        val file = File(dir, sha1(url) + ".img")
        val bytes = withContext(Dispatchers.IO) {
            if (file.isFile) file.readBytes() else downloads.withPermit {
                try {
                    val response = http.get(url)
                    if (!response.status.isSuccess()) null else response.readRawBytes().also { dir.mkdirs(); file.writeBytes(it) }
                } catch (e: Exception) {
                    logger.w(TAG, "poster download failed: ${e::class.simpleName}")
                    null
                }
            }
        } ?: return null
        // Decoded and shrunk to the size it is shown at, off the UI thread: full-size posters
        // decoded on the UI thread made scrolling the library stutter.
        val bitmap = withContext(Dispatchers.Default) {
            try { Image.makeFromEncoded(bytes).let { downscale(it, if (it.width > it.height) 1280 else 360) } } catch (_: Exception) { file.delete(); null }
        } ?: return null
        synchronized(memory) { memory[url] = bitmap }
        return bitmap
    }

    /** The image at most [maxWidth] pixels wide (aspect kept), as a Compose bitmap. */
    private fun downscale(image: Image, maxWidth: Int): ImageBitmap {
        if (image.width <= maxWidth) return image.toComposeImageBitmap()
        val w = maxWidth
        val h = (image.height.toLong() * maxWidth / image.width).toInt().coerceAtLeast(1)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "Posters" }
}
