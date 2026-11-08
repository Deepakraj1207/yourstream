package dev.yourstream.playback.media3

import android.content.Context
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.LanguageSettingsEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SubtitleRepository
import dev.yourstream.library.api.SubtitleSource
import dev.yourstream.playback.tracks.ContentLanguage
import dev.yourstream.playback.tracks.FileContext
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.PreferenceScope
import dev.yourstream.playback.tracks.RememberedChoice
import dev.yourstream.playback.tracks.SubtitleChoice
import dev.yourstream.playback.tracks.SubtitleContent
import dev.yourstream.playback.tracks.SubtitleMode
import dev.yourstream.playback.tracks.TrackOrigin
import dev.yourstream.playback.tracks.TrackPreferenceStore
import dev.yourstream.playback.tracks.TrackSignature
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MessageLookup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/** A subtitle file outside the video, ready for the player (local UTF-8 copy). */
data class SideloadedSubtitle(
    /** "ext:<subtitle track id>": also the Media3 Format id, to find it again among the player's tracks. */
    val id: String,
    val localPath: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val forced: Boolean,
    val sdh: Boolean,
    val fileName: String?,
    val contentLanguage: ContentLanguage?,
    val origin: TrackOrigin,
)

/** Everything the controller needs for track intelligence (null = Media3 defaults only). */
class TrackSupport(
    val store: TrackPreferenceStore,
    val subtitles: ExternalSubtitleLoader? = null,
    val context: TrackContextProvider? = null,
    /** Told what was detected in a file (the library stores probed audio / embedded subtitle tracks). */
    val onAnalyzed: (suspend (PlayableMedia, List<dev.yourstream.playback.tracks.AnalyzedTrack>) -> Unit)? = null,
)

/** Language settings + remembered choices in Room (v6 tables). */
@Singleton
class RoomTrackPreferenceStore @Inject constructor(private val db: YourStreamDatabase) : TrackPreferenceStore {
    private val dao = db.trackDao()

    override suspend fun preferences(): LanguagePreferences = dao.languageSettings()?.let { s ->
        LanguagePreferences(
            audio = s.audioLanguages.split(',').filter { it.isNotBlank() },
            subtitles = s.subtitleLanguages.split(',').filter { it.isNotBlank() },
            fallbackLanguage = s.fallbackLanguage,
            subtitleMode = runCatching { SubtitleMode.valueOf(s.subtitleMode) }.getOrDefault(SubtitleMode.FOREIGN_ONLY),
            avoidCommentary = s.avoidCommentary,
            preferSdh = s.preferSdh,
        ).normalized()
    } ?: LanguagePreferences().normalized()

    override suspend fun setPreferences(preferences: LanguagePreferences) {
        val p = preferences.normalized()
        dao.putLanguageSettings(
            LanguageSettingsEntity(1, p.audio.joinToString(","), p.subtitles.joinToString(","), p.fallbackLanguage, p.subtitleMode.name, p.avoidCommentary, p.preferSdh, System.currentTimeMillis()),
        )
    }

    override suspend fun remembered(itemId: Long): RememberedChoice? {
        val r = dao.preference(itemId) ?: return null
        val scope = r.scope?.let { runCatching { PreferenceScope.valueOf(it) }.getOrNull() } ?: PreferenceScope.MOVIE
        // Rows from before v6 (or from the library API) only know languages: still usable.
        // A language set later through the library API (without a signature) wins over an older signature.
        val audio = TrackSignature.decode(r.audioSignature)?.takeIf { r.audioLanguage == null || it.language == r.audioLanguage }
            ?: r.audioLanguage?.let { TrackSignature(it, null) }
        val subtitle = when {
            r.subtitleSignature != null -> TrackSignature.decode(r.subtitleSignature)?.let { SubtitleChoice.Track(it) }
            !r.subtitlesEnabled -> SubtitleChoice.Off
            r.subtitleLanguage != null -> SubtitleChoice.Track(TrackSignature(r.subtitleLanguage, null))
            else -> null
        }
        return RememberedChoice(scope, itemId, audio, subtitle)
    }

    override suspend fun rememberAudio(itemId: Long, scope: PreferenceScope, signature: TrackSignature) = update(itemId) {
        it.copy(audioLanguage = signature.language, audioSignature = signature.encode(), scope = scope.name)
    }

    override suspend fun rememberSubtitle(itemId: Long, scope: PreferenceScope, choice: SubtitleChoice) = update(itemId) {
        when (choice) {
            SubtitleChoice.Off -> it.copy(subtitlesEnabled = false, subtitleSignature = null, scope = scope.name)
            is SubtitleChoice.Track -> it.copy(subtitlesEnabled = true, subtitleLanguage = choice.signature.language, subtitleSignature = choice.signature.encode(), scope = scope.name)
        }
    }

    override suspend fun forget(itemId: Long) = dao.deletePreference(itemId)

    private suspend fun update(itemId: Long, change: (TrackPreferenceEntity) -> TrackPreferenceEntity) {
        // Keeps what the row already holds (subtitle offset, the other track's choice).
        val current = dao.preference(itemId) ?: TrackPreferenceEntity(itemId, null, null, subtitlesEnabled = true, subtitleOffsetMs = 0, updatedAt = 0)
        dao.upsertPreference(change(current).copy(updatedAt = System.currentTimeMillis()))
    }
}

/** What the library knows around a file: its name, the languages its name lists, the title's original language. */
@Singleton
class TrackContextProvider @Inject constructor(private val db: YourStreamDatabase) {
    suspend fun context(media: PlayableMedia): FileContext {
        val row = media.mediaId?.let { db.mediaDao().getWithParsed(it) }
        val parsed = row?.parsed
        val original = media.target?.itemId?.let { db.metadataDao().itemMetadata(it)?.originalLanguage }
        return FileContext(
            fileName = media.fileName,
            fileNameAudioLanguages = parsed?.languages?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            fileNameSubtitleLanguages = parsed?.subtitleLanguages?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length <= 3 }.orEmpty(),
            originalLanguage = original,
        )
    }
}

/**
 * External subtitle files for a title: the user's own Telegram subtitle files linked to the
 * movie/episode by the library (EXTERNAL), and files added on the device (LOCAL).
 *
 * Only text formats Media3 can render are used (SRT, ASS/SSA, WebVTT, TTML); bitmap formats
 * (VobSub .sub/.idx, PGS .sup) and files over [maxBytes] are skipped. Each file is downloaded
 * completely (they are small), re-encoded to UTF-8 (subtitles are often Windows-1252 or UTF-16),
 * and its text is checked for its real language.
 */
@Singleton
class ExternalSubtitleLoader @Inject constructor(
    private val subtitles: SubtitleRepository,
    private val db: YourStreamDatabase,
    private val messages: TelegramMessageRepository,
    private val files: TelegramFileRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) {
    var maxBytes: Long = 5L * 1024 * 1024
    var perFileTimeoutMs: Long = 5_000

    /**
     * Prepares every external subtitle of a title *in parallel*, each bounded by
     * [perFileTimeoutMs]: this runs before playback starts, so a slow Telegram must not hold the
     * video back (before: one file after another, 15 s each). A file that is not ready in time is
     * skipped for this playback.
     */
    suspend fun load(target: PlaybackTarget, mediaId: Long?): List<SideloadedSubtitle> {
        val tracks = subtitles.subtitles(target, mediaId).first().filter { it.source != SubtitleSource.EMBEDDED }
        return kotlinx.coroutines.coroutineScope {
            tracks.map { t ->
                async {
                    withTimeoutOrNull(perFileTimeoutMs) {
                        runCatching {
                            when (t.source) {
                                SubtitleSource.LOCAL -> t.localPath?.let { prepare("ext:${t.id}", File(it), t.format, t.language, t.label, t.forced, t.sdh, File(it).name, TrackOrigin.LOCAL) }
                                else -> t.mediaId?.let { telegramFile(it) }?.let { (file, name) -> prepare("ext:${t.id}", file, t.format, t.language, t.label, t.forced, t.sdh, name, TrackOrigin.EXTERNAL) }
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Downloads a subtitle file from the user's Telegram (whole file; it is small). */
    private suspend fun telegramFile(subtitleMediaId: Long): Pair<File, String>? {
        val row = db.mediaDao().getWithParsed(subtitleMediaId)?.media ?: return null
        if (row.sizeBytes > maxBytes || mimeFor(null, row.fileName) == null) return null
        val file = when (val r = messages.lookupMessages(row.chatId, listOf(row.messageId))) {
            is AppResult.Ok -> (r.value.firstOrNull() as? MessageLookup.Found)?.message?.media?.file
            is AppResult.Err -> (files.getFile(row.fileId) as? AppResult.Ok)?.value?.takeIf { it.remoteUniqueId == row.fileUniqueId }
        } ?: return null
        val done = if (file.isDownloadingCompleted) file else {
            files.download(file.id, priority = 16)
            files.observeFile(file.id).first { it.isDownloadingCompleted }
        }
        val path = done.localPath ?: return null
        return File(path) to row.fileName
    }

    private fun prepare(id: String, source: File, format: String?, language: String?, label: String?, forced: Boolean, sdh: Boolean, fileName: String, origin: TrackOrigin): SideloadedSubtitle? {
        val mime = mimeFor(format, fileName) ?: return null
        if (!source.canRead() || source.length() > maxBytes) return null
        val text = decode(source.readBytes())
        val dir = File(appContext.cacheDir, "subtitles").apply { mkdirs() }
        val copy = File(dir, id.replace(':', '_') + "." + fileName.substringAfterLast('.', "srt").lowercase())
        copy.writeText(text, Charsets.UTF_8)
        return SideloadedSubtitle(id, copy.absolutePath, mime, language, label ?: fileName, forced, sdh, fileName, SubtitleContent.guess(text), origin)
    }

    companion object {
        /** Media3 MIME type for a text subtitle format, or null when not renderable. */
        fun mimeFor(format: String?, fileName: String?): String? = when ((format ?: fileName?.substringAfterLast('.', ""))?.lowercase()) {
            "srt", "subrip" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt", "webvtt" -> "text/vtt"
            "ttml", "dfxp", "xml" -> "application/ttml+xml"
            else -> null
        }

        /** BOM → that encoding; valid UTF-8 → UTF-8; otherwise Windows-1252 (common for old .srt files). */
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }
}
