package dev.yourstream.library.data

import dev.yourstream.core.database.dao.EpisodeRow
import dev.yourstream.core.database.dao.LibraryItemRow
import dev.yourstream.core.database.dao.MediaVersionRow
import dev.yourstream.core.database.dao.WatchEntryRow
import dev.yourstream.core.database.entity.AudioTrackEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.core.database.entity.SubtitleTrackEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.core.database.entity.WatchHistoryEntity
import dev.yourstream.core.database.entity.WatchStateEntity
import dev.yourstream.library.api.AudioTrack
import dev.yourstream.library.api.AudioTrackSource
import dev.yourstream.library.api.ContinueWatchingEntry
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.Season
import dev.yourstream.library.api.SubtitleSource
import dev.yourstream.library.api.SubtitleTrack
import dev.yourstream.library.api.TrackPreference
import dev.yourstream.library.api.WatchHistoryEntry
import dev.yourstream.library.api.WatchProgress

internal fun kindOf(name: String) = runCatching { LibraryKind.valueOf(name) }.getOrDefault(LibraryKind.VIDEO)

internal fun LibraryItemRow.toModel() = LibraryItemSummary(
    id = id, kind = kindOf(kind), title = title, year = year, availableCount = availableCount, seasonCount = seasonCount,
    episodeCount = episodeCount, watchedEpisodeCount = watchedCount, maxResolution = maxResolution, totalSize = totalSize,
    addedAt = addedAt, latestAddedAt = latestAddedAt, lastWatchedAt = lastWatchedAt, isFavorite = isFavorite,
    progress = positionMs?.let { WatchProgress(it, durationMs ?: 0, completed, lastWatchedAt) },
    posterUrl = posterUrl,
    metadataTitle = metadataTitle,
)

internal fun EpisodeRow.toModel() = Episode(
    id = id, seriesId = seriesId, seasonId = seasonId, seasonNumber = seasonNumber, number = number, numberEnd = numberEnd,
    absoluteNumber = absoluteNumber, title = title, airDate = airDate, availableCount = availableCount, latestAddedAt = latestAddedAt,
    progress = positionMs?.let { WatchProgress(it, durationMs ?: 0, completed, lastWatchedAt) },
)

internal fun SeasonEntity.toModel() = Season(id, seriesId, number, title, episodeCount)

internal fun MediaVersionRow.toModel() = MediaVersion(
    mediaId = mediaId, chatId = chatId, messageId = messageId, fileName = fileName, sizeBytes = sizeBytes,
    durationSeconds = durationSeconds, resolution = resolution, quality = quality, videoCodec = videoCodec,
    languages = languages?.split(',')?.filter { it.isNotBlank() }.orEmpty(), isDeleted = isDeleted, isDuplicate = isDuplicate,
    episodeId = episodeId, isManual = method == LibrarySynchronizer.METHOD_MANUAL,
    isExcluded = method == LibrarySynchronizer.METHOD_EXCLUDED, downloadState = downloadState,
)

internal fun WatchStateEntity.toProgress() = WatchProgress(positionMs, durationMs, completed, lastWatchedAt)

internal fun WatchEntryRow.toModel() = ContinueWatchingEntry(
    target = PlaybackTarget(itemId, episodeKey.takeIf { it != 0L }), kind = kindOf(kind), title = title, year = year,
    seasonNumber = seasonNumber, episodeNumber = episodeNumber, episodeTitle = episodeTitle,
    progress = WatchProgress(positionMs, durationMs, completed, lastWatchedAt), lastMediaId = lastMediaId,
)

internal fun WatchHistoryEntity.toModel() = WatchHistoryEntry(
    id, PlaybackTarget(itemId, episodeKey.takeIf { it != 0L }), mediaId, startedAt, watchedAt, positionMs, durationMs, completed,
)

internal fun SubtitleTrackEntity.toModel() = SubtitleTrack(
    id = id, itemId = itemId, episodeId = episodeId,
    source = runCatching { SubtitleSource.valueOf(source) }.getOrDefault(SubtitleSource.EXTERNAL),
    mediaId = mediaId, forMediaId = forMediaId, trackIndex = trackIndex, language = language, label = label, format = format,
    forced = forced, sdh = sdh, localPath = localPath,
)

internal fun AudioTrackEntity.toModel() = AudioTrack(
    id, mediaId, trackIndex, runCatching { AudioTrackSource.valueOf(source) }.getOrDefault(AudioTrackSource.PARSED),
    language, codec, channels, label, isDefault,
)

internal fun TrackPreferenceEntity.toModel() = TrackPreference(itemId, audioLanguage, subtitleLanguage, subtitlesEnabled, subtitleOffsetMs)
