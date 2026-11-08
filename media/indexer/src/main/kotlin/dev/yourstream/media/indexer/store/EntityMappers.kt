package dev.yourstream.media.indexer.store

import dev.yourstream.core.database.dao.ChatStatusRow
import dev.yourstream.core.database.dao.MediaSummaryRow
import dev.yourstream.core.database.dao.MediaWithParsed
import dev.yourstream.core.database.entity.MediaEntity
import dev.yourstream.core.database.entity.ParsedMediaEntity
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.DownloadState
import dev.yourstream.media.api.model.DuplicateKind
import dev.yourstream.media.api.model.IndexProgress
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.api.model.IndexedMedia
import dev.yourstream.media.api.model.MediaSummary
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.api.model.ParsedMedia
import dev.yourstream.media.api.model.SourceContentType
import dev.yourstream.media.parser.NormalizedMedia

internal fun List<String>.joined(): String = joinToString(",")
internal fun String.splitList(): List<String> = if (isEmpty()) emptyList() else split(",")

internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback

internal fun NormalizedMedia.toEntity(
    id: Long,
    indexedAt: Long,
    now: Long,
    canonicalMediaId: Long?,
    duplicateKind: String?,
): MediaEntity = MediaEntity(
    id = id,
    chatId = chatId,
    messageId = messageId,
    albumId = albumId,
    messageDate = messageDate,
    editDate = editDate,
    fileId = fileId,
    fileUniqueId = fileUniqueId,
    remoteFileId = remoteFileId,
    fileName = fileName,
    mimeType = mimeType,
    extension = extension,
    sizeBytes = sizeBytes,
    durationSeconds = durationSeconds,
    width = width,
    height = height,
    videoCodec = videoCodec,
    audioCodecs = audioCodecs.joined(),
    supportsStreaming = supportsStreaming,
    mediaType = mediaType.name,
    contentType = contentType.name,
    caption = caption,
    audioTitle = audioTitle,
    audioPerformer = audioPerformer,
    downloadState = downloadState.name,
    downloadedBytes = downloadedBytes,
    localPath = localPath,
    searchTitle = searchTitle,
    fingerprint = fingerprint,
    canonicalMediaId = canonicalMediaId,
    duplicateKind = duplicateKind,
    isDeleted = false,
    deletedAt = null,
    indexedAt = indexedAt,
    updatedAt = now,
    lastSeenAt = now,
)

internal fun ParsedMedia.toEntity(mediaId: Long) = ParsedMediaEntity(
    mediaId = mediaId,
    title = title,
    year = year,
    season = season,
    episode = episode,
    episodeEnd = episodeEnd,
    resolution = resolution,
    quality = quality,
    videoCodec = videoCodec,
    audioCodecs = audioCodecs.joined(),
    hdr = hdr,
    languages = languages.joined(),
    subtitleLanguages = subtitleLanguages.joined(),
    edition = edition,
    releaseGroup = releaseGroup,
    partNumber = partNumber,
    confidence = confidence,
    parserVersion = parserVersion,
    normalizedTitle = normalizedTitle,
    alternativeTitle = alternativeTitle,
    seasonEnd = seasonEnd,
    absoluteEpisode = absoluteEpisode,
    airDate = airDate,
    qualityTags = qualityTags.joined(),
    streamingService = streamingService,
    bitDepth = bitDepth,
    audioChannels = audioChannels,
    audioBitrateKbps = audioBitrateKbps,
    dualAudio = isDualAudio,
    multiAudio = isMultiAudio,
    dubbed = isDubbed,
    subtitlesHardcoded = subtitlesHardcoded,
    flags = flags.sorted().joined(),
    kind = kind,
    fieldConfidence = fieldConfidence.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${"%.2f".format(java.util.Locale.ROOT, it.value)}" },
    warnings = warnings.joinToString("\n"),
)

internal fun ParsedMediaEntity.toModel() = ParsedMedia(
    title = title,
    normalizedTitle = normalizedTitle,
    alternativeTitle = alternativeTitle,
    year = year,
    season = season,
    seasonEnd = seasonEnd,
    episode = episode,
    episodeEnd = episodeEnd,
    absoluteEpisode = absoluteEpisode,
    airDate = airDate,
    resolution = resolution,
    quality = quality,
    qualityTags = qualityTags.splitList(),
    streamingService = streamingService,
    videoCodec = videoCodec,
    bitDepth = bitDepth,
    hdr = hdr,
    audioCodecs = audioCodecs.splitList(),
    audioChannels = audioChannels,
    audioBitrateKbps = audioBitrateKbps,
    isDualAudio = dualAudio,
    isMultiAudio = multiAudio,
    languages = languages.splitList(),
    isDubbed = dubbed,
    subtitleLanguages = subtitleLanguages.splitList(),
    subtitlesHardcoded = subtitlesHardcoded,
    edition = edition,
    releaseGroup = releaseGroup,
    partNumber = partNumber,
    flags = flags.splitList().toSet(),
    kind = kind,
    fieldConfidence = fieldConfidence.split(';').mapNotNull { pair ->
        val (k, v) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        v.toFloatOrNull()?.let { k to it }
    }.toMap(),
    confidence = confidence,
    warnings = if (warnings.isEmpty()) emptyList() else warnings.split('\n'),
    parserVersion = parserVersion,
)

internal fun MediaWithParsed.toModel(chatTitle: String?) = IndexedMedia(
    id = media.id,
    chatId = media.chatId,
    chatTitle = chatTitle,
    messageId = media.messageId,
    albumId = media.albumId,
    messageDate = media.messageDate,
    editDate = media.editDate,
    fileId = media.fileId,
    fileUniqueId = media.fileUniqueId,
    remoteFileId = media.remoteFileId,
    fileName = media.fileName,
    mimeType = media.mimeType,
    extension = media.extension,
    sizeBytes = media.sizeBytes,
    durationSeconds = media.durationSeconds,
    width = media.width,
    height = media.height,
    videoCodec = media.videoCodec,
    audioCodecs = media.audioCodecs.splitList(),
    supportsStreaming = media.supportsStreaming,
    mediaType = enumOr(media.mediaType, MediaType.OTHER),
    contentType = enumOr(media.contentType, SourceContentType.DOCUMENT),
    caption = media.caption,
    audioTitle = media.audioTitle,
    audioPerformer = media.audioPerformer,
    downloadState = enumOr(media.downloadState, DownloadState.NOT_DOWNLOADED),
    downloadedBytes = media.downloadedBytes,
    localPath = media.localPath,
    canonicalMediaId = media.canonicalMediaId,
    duplicateKind = media.duplicateKind?.let { enumOr(it, DuplicateKind.EXACT) },
    isDeleted = media.isDeleted,
    deletedAt = media.deletedAt,
    indexedAt = media.indexedAt,
    updatedAt = media.updatedAt,
    parsed = parsed?.toModel(),
)

internal fun MediaSummaryRow.toModel() = MediaSummary(
    id = id,
    chatId = chatId,
    messageId = messageId,
    fileName = fileName,
    mediaType = enumOr(mediaType, MediaType.OTHER),
    sizeBytes = sizeBytes,
    durationSeconds = durationSeconds,
    messageDate = messageDate,
    parsedTitle = parsedTitle,
    season = season,
    episode = episode,
    absoluteEpisode = absoluteEpisode,
    resolution = resolution,
    isDuplicate = isDuplicate,
    isDeleted = isDeleted,
)

internal fun ChatStatusRow.toModel(progress: IndexProgress?) = ChatIndexStatus(
    chatId = chat.chatId,
    title = chat.title,
    enabled = chat.enabled,
    state = enumOr(chat.state, IndexState.IDLE),
    mediaCount = mediaCount,
    deletedCount = deletedCount,
    backfillComplete = backfillComplete,
    lastIndexedAt = chat.lastIndexedAt,
    lastVerifiedAt = chat.lastVerifiedAt,
    lastError = chat.lastError,
    progress = progress,
)
