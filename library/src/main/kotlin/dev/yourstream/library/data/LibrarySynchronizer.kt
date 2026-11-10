package dev.yourstream.library.data

import dev.yourstream.media.filename.MediaFilenameParser

import androidx.room.withTransaction
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.LinkInputRow
import dev.yourstream.core.database.entity.AudioTrackEntity
import dev.yourstream.core.database.entity.EpisodeEntity
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.LibraryItemLanguageEntity
import dev.yourstream.core.database.entity.MediaLinkEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.core.database.entity.SubtitleTrackEntity
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibrarySyncResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns index changes into library structure. Work comes from two trigger-filled queues:
 *  - library_pending: files inserted/updated/re-parsed → (re)link them;
 *  - library_dirty_item: items that lost links through cascades → recompute/clean up.
 *
 * Linking rules (AUTO links only; MANUAL and EXCLUDED links are never touched):
 *  - duplicate of another file → same item/episode as the canonical file (a "version");
 *  - parsed as an episode (SxxEyy, absolute number, air date) → SERIES "series:<title key>",
 *    its season and episode;
 *  - parsed as a season pack → the SERIES, no episode;
 *  - otherwise with a usable title → MOVIE "movie:<title key>:<year>" (a year-less file joins
 *    the only dated movie with that title, if exactly one exists);
 *  - no usable title → standalone VIDEO "video:<media id>".
 * Subtitle files attach to the matching movie/series/episode as EXTERNAL subtitle tracks.
 *
 * Everything runs in batches of [BATCH] files, one transaction each, so memory stays flat and
 * the UI sees consistent states.
 */
@Singleton
class LibrarySynchronizer @Inject constructor(
    private val db: YourStreamDatabase,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val dao = db.libraryDao()
    private val tracks = db.trackDao()
    private val mutex = Mutex()

    private class Batch {
        val touchedItems = linkedSetOf<Long>()
        var processed = 0
        var linksChanged = 0
        var itemsCreated = 0
        var subtitles = 0
    }

    suspend fun sync(): LibrarySyncResult = mutex.withLock {
        var processed = 0
        var linksChanged = 0
        var created = 0
        var removed = 0
        var subtitles = 0

        while (true) {
            val ids = dao.pendingMedia(BATCH)
            if (ids.isEmpty()) break
            val batch = Batch()
            db.withTransaction {
                val rows = dao.linkInputs(ids).sortedBy { it.mediaId }
                rows.forEach { process(it, batch) }
                dao.clearPending(ids)
                removed += finishItems(batch.touchedItems)
            }
            processed += batch.processed; linksChanged += batch.linksChanged; created += batch.itemsCreated; subtitles += batch.subtitles
            yield()
        }

        while (true) {
            val dirty = dao.dirtyItems(BATCH)
            if (dirty.isEmpty()) break
            db.withTransaction {
                removed += finishItems(dirty.toSet())
                dao.clearDirty(dirty)
            }
            yield()
        }

        // Subtitle files whose movie/episode was not in the library yet.
        val waiting = tracks.unattachedSubtitleFiles(BATCH)
        if (waiting.isNotEmpty()) {
            val batch = Batch()
            db.withTransaction { dao.linkInputs(waiting).forEach { attachSubtitle(it, batch) } }
            subtitles += batch.subtitles
        }

        val result = LibrarySyncResult(processed, linksChanged, created, removed, subtitles)
        if (processed > 0 || removed > 0) logger.i(TAG, "library sync: $result")
        result
    }

    // ------------------------------------------------------------------ per file

    private suspend fun process(row: LinkInputRow, batch: Batch) {
        batch.processed++
        when (row.mediaType) {
            "SUBTITLE" -> attachSubtitle(row, batch)
            "VIDEO" -> linkVideo(row, batch)
            else -> row.linkItemId?.let { dao.deleteLink(row.mediaId); batch.touchedItems += it }
        }
    }

    private suspend fun linkVideo(row: LinkInputRow, batch: Batch) {
        row.linkItemId?.let { batch.touchedItems += it }
        if (row.linkMethod == METHOD_MANUAL || row.linkMethod == METHOD_EXCLUDED) return
        val signature = signature(row)
        if (row.linkItemId != null && row.linkSignature == signature) return

        val target = resolveTarget(row, batch)
        dao.upsertLink(MediaLinkEntity(row.mediaId, target.first, target.second, METHOD_AUTO, signature, clock.nowMillis()))
        batch.touchedItems += target.first
        if (row.linkItemId != target.first || row.linkEpisodeId != target.second) batch.linksChanged++
        if (!row.isDeleted) writeParsedAudio(row)
    }

    /** (item id, episode id) for a video file. */
    private suspend fun resolveTarget(row: LinkInputRow, batch: Batch): Pair<Long, Long?> {
        row.canonicalMediaId?.let { canonical ->
            dao.link(canonical)?.takeIf { it.method != METHOD_EXCLUDED }?.let { return it.itemId to it.episodeId }
        }
        val title = row.title?.takeIf { it.isNotBlank() }
        val key = (row.normalizedTitle ?: title?.let(LibraryQueryBuilder::normalize))?.takeIf { it.isNotBlank() }
        val titleConfidence = fieldConfidence(row.fieldConfidence, "title") ?: row.confidence ?: 0f
        if (title == null || key == null || titleConfidence < MIN_TITLE_CONFIDENCE) {
            // Never show a machine-made name (hash, Telegram id) as a title.
            // Nor a list of tags ("Tel + Hin 450MB.mkv", "Tel.mkv").
            fun named(n: String) = n.isNotBlank() && !MediaFilenameParser.isMachineName(n) &&
                !MediaFilenameParser.isTagsOnly(n.substringBeforeLast('.').replace(Regex("[._]+"), " "), otherLanguages = 1)
            val name = title?.takeIf(::named) ?: row.fileName.takeIf(::named) ?: UNTITLED
            return ensureItem(LibraryKind.VIDEO, "video:${row.mediaId}", name, key ?: LibraryQueryBuilder.normalize(name), null, titleConfidence, batch) to null
        }

        val episodic = row.episode != null || row.absoluteEpisode != null || row.airDate != null
        if (episodic || row.season != null) {
            val seriesId = ensureItem(LibraryKind.SERIES, "series:$key", title, key, null, titleConfidence, batch)
            if (!episodic) return seriesId to null // season pack: belongs to the series, no episode
            val (seasonNumber, episodeNumber) = when {
                row.episode != null -> (row.season ?: SeasonEntity.ABSOLUTE) to row.episode!!
                row.absoluteEpisode != null -> SeasonEntity.ABSOLUTE to row.absoluteEpisode!!
                else -> dateNumbering(row.airDate!!)
            }
            val episodeId = ensureEpisode(seriesId, seasonNumber, episodeNumber, row)
            return seriesId to episodeId
        }

        val movieKey = if (row.year != null) "movie:$key:${row.year}" else {
            val dated = dao.moviesByTitle(key)
            if (dated.size == 1) dao.item(dated.single())?.matchKey ?: "movie:$key:" else "movie:$key:"
        }
        return ensureItem(LibraryKind.MOVIE, movieKey, title, key, row.year, titleConfidence, batch) to null
    }

    private suspend fun ensureItem(
        kind: LibraryKind, matchKey: String, title: String, key: String, year: Int?, confidence: Float, batch: Batch,
    ): Long {
        dao.aliasTarget(matchKey)?.let { return it }
        dao.itemIdByKey(matchKey)?.let { id ->
            val item = dao.item(id)!!
            // A clearer name later (higher title confidence) replaces a poor one, unless edited.
            if (!item.locked && confidence > item.titleConfidence + 0.1f && title != item.title) {
                dao.updateItem(item.copy(title = title, sortTitle = LibraryQueryBuilder.sortTitle(title), titleConfidence = confidence))
            }
            return id
        }
        val now = clock.nowMillis()
        val id = dao.insertItem(
            LibraryItemEntity(
                kind = kind.name, matchKey = matchKey, title = title, sortTitle = LibraryQueryBuilder.sortTitle(title),
                normalizedTitle = key, year = year, searchText = key, titleConfidence = confidence, addedAt = now, updatedAt = now,
            ),
        )
        batch.itemsCreated++
        return if (id > 0) id else dao.itemIdByKey(matchKey)!!
    }

    private suspend fun ensureEpisode(seriesId: Long, seasonNumber: Int, number: Int, row: LinkInputRow): Long {
        val seasonId = dao.seasonId(seriesId, seasonNumber)
            ?: dao.insertSeason(SeasonEntity(seriesId = seriesId, number = seasonNumber, title = null)).takeIf { it > 0 }
            ?: dao.seasonId(seriesId, seasonNumber)!!
        val existing = dao.episodeId(seasonId, number)
        if (existing != null) {
            dao.enrichEpisode(existing, row.episodeEnd, row.absoluteEpisode, row.airDate)
            return existing
        }
        return dao.insertEpisode(
            EpisodeEntity(
                seriesId = seriesId, seasonId = seasonId, seasonNumber = seasonNumber, number = number,
                numberEnd = row.episodeEnd, absoluteNumber = row.absoluteEpisode, title = null, airDate = row.airDate,
            ),
        ).takeIf { it > 0 } ?: dao.episodeId(seasonId, number)!!
    }

    /** Daily shows: season = year, episode = MMDD. */
    private fun dateNumbering(airDate: String): Pair<Int, Int> {
        val parts = airDate.split('-')
        return parts[0].toInt() to (parts[1].toInt() * 100 + parts[2].toInt())
    }

    private suspend fun attachSubtitle(row: LinkInputRow, batch: Batch) {
        if (row.isDeleted) { tracks.deleteSubtitleForFile(row.mediaId); return }
        val key = row.normalizedTitle?.takeIf { it.isNotBlank() }
        var itemId: Long? = null
        var episodeId: Long? = null
        if (key != null) {
            val episodic = row.episode != null || row.absoluteEpisode != null
            if (episodic || row.season != null) {
                itemId = dao.aliasTarget("series:$key") ?: dao.seriesByTitle(key)
                if (itemId != null && episodic) {
                    val season = if (row.episode != null) row.season ?: SeasonEntity.ABSOLUTE else SeasonEntity.ABSOLUTE
                    val number = row.episode ?: row.absoluteEpisode!!
                    episodeId = dao.seasonId(itemId, season)?.let { dao.episodeId(it, number) }
                }
            } else {
                itemId = row.year?.let { dao.aliasTarget("movie:$key:$it") ?: dao.itemIdByKey("movie:$key:$it") }
                    ?: dao.moviesByTitle(key).singleOrNull()
                    ?: dao.itemIdByKey("movie:$key:")
            }
        }
        val existing = tracks.subtitleForFile(row.mediaId)
        tracks.upsertSubtitle(
            SubtitleTrackEntity(
                id = existing?.id ?: 0, itemId = itemId, episodeId = episodeId, mediaId = row.mediaId, forMediaId = null,
                trackIndex = null, source = "EXTERNAL", language = row.subtitleLanguages?.split(',')?.firstOrNull { it.isNotBlank() },
                label = row.fileName, format = row.extension, forced = false, sdh = false, localPath = null,
                addedAt = existing?.addedAt ?: clock.nowMillis(),
            ),
        )
        if (itemId != null && existing?.itemId != itemId) batch.subtitles++
    }

    /** One PARSED audio track per language named in the file name (refined by probing later). */
    private suspend fun writeParsedAudio(row: LinkInputRow) {
        tracks.deleteAudio(row.mediaId, "PARSED")
        val languages = row.languages?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val codec = row.audioCodecs?.split(',')?.firstOrNull { it.isNotBlank() }
        if (languages.isEmpty() && codec == null) return
        val list = languages.ifEmpty { listOf(null) }
        tracks.insertAudio(list.mapIndexed { i, lang ->
            AudioTrackEntity(mediaId = row.mediaId, trackIndex = i, source = "PARSED", language = lang, codec = codec,
                channels = row.audioChannels, label = null, isDefault = i == 0)
        })
    }

    // ------------------------------------------------------------------ aggregates & cleanup

    /** Recomputes cached columns of touched items, then deletes the ones nobody needs. Returns removals. */
    private suspend fun finishItems(itemIds: Set<Long>): Int {
        for (id in itemIds) recompute(id)
        return itemIds.chunked(500).sumOf { dao.deleteOrphanItems(it) }
    }

    suspend fun recompute(itemId: Long) {
        val item = dao.item(itemId) ?: return
        val isSeries = item.kind == LibraryKind.SERIES.name
        if (isSeries) {
            dao.recomputeEpisodes(itemId)
            dao.deleteOrphanEpisodes(itemId)
            dao.recomputeSeasons(itemId)
            dao.deleteEmptySeasons(itemId)
        }
        val counts = dao.itemCounts(itemId)
        val languages = dao.itemLanguageLists(itemId).flatMap { it.split(',') }.filter { it.isNotBlank() }.toSortedSet()
        dao.clearLanguages(itemId)
        if (languages.isNotEmpty()) dao.insertLanguages(languages.map { LibraryItemLanguageEntity(itemId, it) })
        val searchText = (listOf(item.normalizedTitle) + dao.itemAlternativeTitles(itemId).map(LibraryQueryBuilder::normalize) +
            listOfNotNull(item.year?.toString()) + languages.mapNotNull(LANGUAGE_NAMES::get)).distinct().joinToString(" ")
        dao.setItemAggregates(
            id = itemId,
            available = counts.availableCount,
            latestAddedAt = counts.latestAddedAt ?: item.latestAddedAt,
            maxResolution = dao.itemMaxResolution(itemId),
            totalSize = dao.itemTotalSize(itemId),
            episodeCount = if (isSeries) dao.availableEpisodeCount(itemId) else 0,
            seasonCount = if (isSeries) dao.availableSeasonCount(itemId) else 0,
            searchText = searchText,
            now = clock.nowMillis(),
        )
    }

    private fun signature(row: LinkInputRow): String = listOf(
        row.mediaType, row.normalizedTitle, row.title, row.year, row.season, row.episode, row.episodeEnd, row.absoluteEpisode,
        row.airDate, row.kind, row.canonicalMediaId, row.isDeleted, row.parserVersion,
    ).joinToString("|")

    private fun fieldConfidence(encoded: String?, field: String): Float? =
        encoded?.split(';')?.firstOrNull { it.startsWith("$field=") }?.substringAfter('=')?.toFloatOrNull()

    companion object {
        private const val TAG = "LibrarySync"
        const val BATCH = 500
        /** Shown for files nothing names (no usable file name, caption or chat title). */
        const val UNTITLED = "Untitled video"
        const val METHOD_AUTO = "AUTO"
        const val METHOD_MANUAL = "MANUAL"
        const val METHOD_EXCLUDED = "EXCLUDED"

        /** Below this, a parsed title is not trusted to identify a movie or series. */
        const val MIN_TITLE_CONFIDENCE = 0.5f

        /** Language names added to search text so "tamil" finds Tamil items. */
        val LANGUAGE_NAMES = mapOf(
            "ta" to "tamil", "te" to "telugu", "hi" to "hindi", "ml" to "malayalam", "kn" to "kannada", "bn" to "bengali",
            "mr" to "marathi", "pa" to "punjabi", "gu" to "gujarati", "en" to "english", "ko" to "korean", "ja" to "japanese",
            "zh" to "chinese", "es" to "spanish", "fr" to "french", "de" to "german", "ar" to "arabic", "ur" to "urdu",
        )
    }
}
