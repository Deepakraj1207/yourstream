package dev.yourstream.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import dev.yourstream.playback.stream.TelegramFileStreamer

/**
 * Builds Media3 sources for Telegram files (and delegates any other URI to Media3's default
 * factory, e.g. local subtitle files later). Telegram files are progressive containers
 * (MP4/MKV/WebM/TS/…): a [ProgressiveMediaSource] reading through [TelegramMediaDataSource].
 *
 * Seeking cost is decided by the container index: MP4 (moov) and MKV (cues) map a time to an
 * exact byte offset, so a seek opens the data source right there. Containers without an index
 * (MP3/ADTS/AMR) use constant-bitrate seeking instead of reading from the start.
 */
@OptIn(UnstableApi::class)
class TelegramMediaSourceFactory(
    streamer: TelegramFileStreamer,
    private val fallback: MediaSource.Factory? = null,
    retryPolicy: LoadErrorHandlingPolicy = TelegramLoadErrorHandlingPolicy(),
    /** For sideloaded subtitle files (local copies). */
    private val context: android.content.Context? = null,
) : MediaSource.Factory {

    private val subtitleParsers = DefaultSubtitleParserFactory()
    private val extractors = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
        // Embedded text tracks (MKV SRT/ASS, MP4 tx3g/WebVTT …) are parsed while extracting.
        .setSubtitleParserFactory(subtitleParsers)
    private var loadErrorPolicy: LoadErrorHandlingPolicy = retryPolicy
    private var progressive = progressiveFactory(TelegramMediaDataSource.Factory(streamer))
    private val dataSourceFactory = TelegramMediaDataSource.Factory(streamer)

    private fun progressiveFactory(dataSource: TelegramMediaDataSource.Factory) =
        ProgressiveMediaSource.Factory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            // Ask the load control after every 512 KB: memory stays bounded on big files.
            .setContinueLoadingCheckIntervalBytes(512 * 1024)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        fallback?.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        loadErrorPolicy = loadErrorHandlingPolicy
        progressive = progressiveFactory(dataSourceFactory)
        fallback?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme == TelegramMediaUri.SCHEME) {
            val video = progressive.createMediaSource(mediaItem)
            val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty().mapNotNull(::subtitleSource)
            return if (subtitles.isEmpty()) video else MergingMediaSource(video, *subtitles.toTypedArray())
        }
        return (fallback ?: error("No factory for $uri")).createMediaSource(mediaItem)
    }

    /** A sideloaded text subtitle (local file) as its own source, parsed with Media3's subtitle parsers. */
    private fun subtitleSource(config: MediaItem.SubtitleConfiguration): MediaSource? {
        val ctx = context ?: return null
        val format = Format.Builder()
            .setSampleMimeType(config.mimeType)
            .setLanguage(config.language)
            .setLabel(config.label)
            .setId(config.id)
            .setSelectionFlags(config.selectionFlags)
            .setRoleFlags(config.roleFlags)
            .build()
        if (!subtitleParsers.supportsFormat(format)) return null
        val extractorsFactory = ExtractorsFactory { arrayOf(SubtitleExtractor(subtitleParsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx), extractorsFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(MediaItem.fromUri(config.uri))
    }

    companion object {
        fun withDefaults(streamer: TelegramFileStreamer, context: android.content.Context, retryPolicy: LoadErrorHandlingPolicy = TelegramLoadErrorHandlingPolicy()) =
            TelegramMediaSourceFactory(streamer, DefaultMediaSourceFactory(context), retryPolicy, context)
    }
}
