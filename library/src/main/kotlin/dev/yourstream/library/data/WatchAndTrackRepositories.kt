package dev.yourstream.library.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.AudioTrackEntity
import dev.yourstream.core.database.entity.SubtitleTrackEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.core.database.entity.WatchHistoryEntity
import dev.yourstream.core.database.entity.WatchStateEntity
import dev.yourstream.library.api.AudioTrack
import dev.yourstream.library.api.AudioTrackRepository
import dev.yourstream.library.api.AudioTrackSource
import dev.yourstream.library.api.ContinueWatchingEntry
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SubtitleRepository
import dev.yourstream.library.api.SubtitleSource
import dev.yourstream.library.api.SubtitleTrack
import dev.yourstream.library.api.TrackPreference
import dev.yourstream.library.api.WatchHistoryEntry
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.library.api.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** When does a position count as "watched"? Pure, tested. */
object CompletionPolicy {
    /** Positions below this are not worth resuming (a peek), but still count as "watched recently". */
    const val MIN_RESUME_MS = 30_000L
    private const val LONG_FORM_MS = 20 * 60_000L
    private const val CREDITS_MS = 3 * 60_000L

    fun isComplete(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        if (positionMs >= durationMs * 9 / 10) return true
        return durationMs >= LONG_FORM_MS && durationMs - positionMs <= CREDITS_MS
    }
}

@Singleton
class RoomWatchHistoryRepository @Inject constructor(
    private val db: YourStreamDatabase,
    private val clock: Clock,
) : WatchHistoryRepository {

    private val watch = db.watchDao()
    private val library = db.libraryDao()
    private var insertsSinceTrim = 0

    override suspend fun updateProgress(target: PlaybackTarget, mediaId: Long?, positionMs: Long, durationMs: Long): WatchProgress =
        db.withTransaction {
            val now = clock.nowMillis()
            val position = positionMs.coerceAtLeast(0)
            val duration = durationMs.coerceAtLeast(0)
            val previous = watch.state(target.itemId, target.episodeKey)
            val completedNow = CompletionPolicy.isComplete(position, duration)
            val becameComplete = completedNow && previous?.completed != true
            val state = WatchStateEntity(
                itemId = target.itemId,
                episodeKey = target.episodeKey,
                // Finished → resume from the start next time; a short peek is not a resume point.
                positionMs = if (completedNow || position < CompletionPolicy.MIN_RESUME_MS) 0 else position,
                durationMs = duration,
                completed = completedNow || (previous?.completed == true && position < CompletionPolicy.MIN_RESUME_MS),
                completedAt = if (becameComplete) now else previous?.completedAt,
                playCount = (previous?.playCount ?: 0) + if (becameComplete) 1 else 0,
                lastWatchedAt = now,
                lastMediaId = mediaId ?: previous?.lastMediaId,
            )
            watch.upsertState(state)
            recordSession(target, mediaId, position, duration, completedNow, now)
            refreshAggregates(target.itemId)
            state.toProgress()
        }

    /** One history row per sitting: updated while playback continues, new after a 30 min gap. */
    private suspend fun recordSession(target: PlaybackTarget, mediaId: Long?, position: Long, duration: Long, completed: Boolean, now: Long) {
        val last = watch.lastSession(target.itemId, target.episodeKey)
        if (last != null && now - last.watchedAt <= SESSION_GAP_MS) {
            watch.updateHistory(last.copy(watchedAt = now, positionMs = position, durationMs = duration, completed = completed || last.completed, mediaId = mediaId ?: last.mediaId))
        } else {
            watch.insertHistory(WatchHistoryEntity(itemId = target.itemId, episodeKey = target.episodeKey, mediaId = mediaId,
                startedAt = now, watchedAt = now, positionMs = position, durationMs = duration, completed = completed))
            if (++insertsSinceTrim >= 100) { watch.trimHistory(HISTORY_LIMIT); insertsSinceTrim = 0 }
        }
    }

    override suspend fun markWatched(target: PlaybackTarget, watched: Boolean) {
        db.withTransaction {
            val item = library.item(target.itemId) ?: return@withTransaction
            val keys = if (item.kind == LibraryKind.SERIES.name && target.episodeId == null) library.episodeIds(item.id) else listOf(target.episodeKey)
            setWatched(target.itemId, keys, watched)
            refreshAggregates(target.itemId)
        }
    }

    override suspend fun markSeasonWatched(seasonId: Long, watched: Boolean) {
        db.withTransaction {
            val seriesId = library.seriesOfSeason(seasonId) ?: return@withTransaction
            setWatched(seriesId, library.episodeIdsOfSeason(seasonId), watched)
            refreshAggregates(seriesId)
        }
    }

    private suspend fun setWatched(itemId: Long, keys: List<Long>, watched: Boolean) {
        val now = clock.nowMillis()
        val states = keys.map { key ->
            val previous = watch.state(itemId, key)
            WatchStateEntity(
                itemId = itemId, episodeKey = key, positionMs = 0, durationMs = previous?.durationMs ?: 0,
                completed = watched, completedAt = if (watched) now else null,
                playCount = (previous?.playCount ?: 0) + if (watched && previous?.completed != true) 1 else 0,
                lastWatchedAt = now, lastMediaId = previous?.lastMediaId,
            )
        }
        if (watched) watch.upsertStates(states) else keys.forEach { watch.deleteState(itemId, it) }
    }

    override suspend fun clearProgress(target: PlaybackTarget) {
        db.withTransaction {
            watch.deleteState(target.itemId, target.episodeKey)
            watch.clearHistory(target.itemId, target.episodeKey)
            refreshAggregates(target.itemId)
        }
    }

    /** Keeps library_item.watched_count / last_watched_at (used by list filters/sorts) current. */
    suspend fun refreshAggregates(itemId: Long) {
        val item = library.item(itemId) ?: return
        val watched = if (item.kind == LibraryKind.SERIES.name) watch.completedEpisodeCount(itemId)
        else if (watch.state(itemId, 0)?.completed == true) 1 else 0
        watch.setItemWatchAggregates(itemId, watched, watch.lastWatched(itemId))
    }

    override fun progress(target: PlaybackTarget): Flow<WatchProgress?> =
        watch.observeState(target.itemId, target.episodeKey).map { it?.toProgress() }

    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingEntry>> =
        watch.continueWatching(limit).map { rows -> rows.map { it.toModel() } }

    override fun recentlyWatched(pageSize: Int): Flow<PagingData<ContinueWatchingEntry>> =
        Pager(PagingConfig(pageSize, enablePlaceholders = false)) { watch.recentlyWatched() }.flow.map { it.map { r -> r.toModel() } }

    override fun history(pageSize: Int): Flow<PagingData<WatchHistoryEntry>> =
        Pager(PagingConfig(pageSize, enablePlaceholders = false)) { watch.history() }.flow.map { it.map { r -> r.toModel() } }

    private companion object {
        const val SESSION_GAP_MS = 30 * 60_000L
        const val HISTORY_LIMIT = 5_000
    }
}

/** Shared by the subtitle and audio repositories: the per-item track preference row. */
@Singleton
class TrackPreferenceStore @Inject constructor(db: YourStreamDatabase, private val clock: Clock) {
    private val tracks = db.trackDao()

    suspend fun get(itemId: Long): TrackPreference? = tracks.preference(itemId)?.toModel()

    suspend fun update(itemId: Long, change: (TrackPreferenceEntity) -> TrackPreferenceEntity) {
        val current = tracks.preference(itemId) ?: TrackPreferenceEntity(itemId, null, null, true, 0, clock.nowMillis())
        tracks.upsertPreference(change(current).copy(updatedAt = clock.nowMillis()))
    }
}

@Singleton
class RoomSubtitleRepository @Inject constructor(
    db: YourStreamDatabase,
    private val preferences: TrackPreferenceStore,
    private val clock: Clock,
) : SubtitleRepository {
    private val tracks = db.trackDao()

    override fun subtitles(target: PlaybackTarget, mediaId: Long?): Flow<List<SubtitleTrack>> =
        tracks.subtitlesFor(target.itemId, target.episodeId, mediaId).map { list -> list.map { it.toModel() } }

    override suspend fun addLocal(target: PlaybackTarget, path: String, language: String?, label: String?, format: String?): Long =
        tracks.upsertSubtitle(
            SubtitleTrackEntity(itemId = target.itemId, episodeId = target.episodeId, mediaId = null, forMediaId = null, trackIndex = null,
                source = SubtitleSource.LOCAL.name, language = language, label = label, format = format, forced = false, sdh = false,
                localPath = path, addedAt = clock.nowMillis()),
        )

    override suspend fun replaceEmbedded(mediaId: Long, tracks: List<SubtitleTrack>) {
        this.tracks.deleteEmbedded(mediaId)
        this.tracks.insertSubtitles(tracks.mapIndexed { i, t ->
            SubtitleTrackEntity(itemId = t.itemId, episodeId = t.episodeId, mediaId = null, forMediaId = mediaId, trackIndex = t.trackIndex ?: i,
                source = SubtitleSource.EMBEDDED.name, language = t.language, label = t.label, format = t.format, forced = t.forced,
                sdh = t.sdh, localPath = null, addedAt = clock.nowMillis())
        })
    }

    override suspend fun remove(trackId: Long) = tracks.deleteSubtitle(trackId)

    override suspend fun preference(itemId: Long): TrackPreference? = preferences.get(itemId)

    override suspend fun setSubtitlePreference(itemId: Long, language: String?, enabled: Boolean, offsetMs: Long) =
        preferences.update(itemId) { it.copy(subtitleLanguage = language, subtitlesEnabled = enabled, subtitleOffsetMs = offsetMs) }
}

@Singleton
class RoomAudioTrackRepository @Inject constructor(
    db: YourStreamDatabase,
    private val preferences: TrackPreferenceStore,
) : AudioTrackRepository {
    private val tracks = db.trackDao()

    override suspend fun tracks(mediaId: Long): List<AudioTrack> {
        val probed = tracks.audioTracks(mediaId, AudioTrackSource.PROBED.name)
        return (probed.ifEmpty { tracks.audioTracks(mediaId, AudioTrackSource.PARSED.name) }).map { it.toModel() }
    }

    override suspend fun replaceProbed(mediaId: Long, tracks: List<AudioTrack>) {
        this.tracks.deleteAudio(mediaId, AudioTrackSource.PROBED.name)
        this.tracks.insertAudio(tracks.mapIndexed { i, t ->
            AudioTrackEntity(mediaId = mediaId, trackIndex = t.trackIndex.takeIf { it >= 0 } ?: i, source = AudioTrackSource.PROBED.name,
                language = t.language, codec = t.codec, channels = t.channels, label = t.label, isDefault = t.isDefault)
        })
    }

    override suspend fun languages(itemId: Long): List<String> = tracks.audioLanguagesOfItem(itemId)

    override suspend fun preferredLanguage(itemId: Long): String? = preferences.get(itemId)?.audioLanguage

    override suspend fun setPreferredLanguage(itemId: Long, language: String?) =
        preferences.update(itemId) { it.copy(audioLanguage = language) }
}
