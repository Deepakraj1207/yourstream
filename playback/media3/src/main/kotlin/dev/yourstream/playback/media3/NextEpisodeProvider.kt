package dev.yourstream.playback.media3

import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.playback.adaptive.NextMediaProvider
import dev.yourstream.playback.adaptive.PrefetchCandidate

/**
 * The next episode of whatever is playing, resolved like any playback (fresh Telegram file id,
 * availability), or null for movies / the last episode / unavailable files.
 */
class NextEpisodeProvider(
    private val episodes: EpisodeRepository,
    private val resolver: MediaSourceResolver,
) : NextMediaProvider {
    @Volatile var current: PlayableMedia? = null

    override suspend fun next(): PrefetchCandidate? {
        val target = current?.target ?: return null
        val episodeId = target.episodeId ?: return null
        val next = episodes.nextEpisode(episodeId) ?: return null
        val media = (resolver.resolve(next.target) as? AppResult.Ok)?.value ?: return null
        return PrefetchCandidate(media.fileId, media.sizeBytes, media.durationMs, next.label)
    }
}
