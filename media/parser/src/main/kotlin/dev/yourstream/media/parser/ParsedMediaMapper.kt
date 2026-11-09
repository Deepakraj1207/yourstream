package dev.yourstream.media.parser

import dev.yourstream.media.api.model.ParsedMedia
import dev.yourstream.media.filename.FilenameMetadata
import dev.yourstream.media.filename.MediaFilenameParser
import dev.yourstream.media.filename.ParseHints

/** Bridge from the standalone :media:filename library to the index's [ParsedMedia]. */
object ParsedMediaMapper {

    val PARSER_VERSION: Int get() = MediaFilenameParser.VERSION

    fun parse(fileName: String, caption: String?, chatTitle: String?, context: String? = null): ParsedMedia =
        MediaFilenameParser.parse(fileName, ParseHints(caption = caption, chatTitle = chatTitle, context = context?.ifBlank { null })).toParsedMedia()

    /** No title of its own (missing, or guessed from the chat's name): the post above the file may have it. */
    fun needsContext(p: ParsedMedia): Boolean =
        p.title == null || p.warnings.any { it == MediaFilenameParser.CHAT_TITLE_WARNING || it == MediaFilenameParser.GENERIC_TITLE_WARNING }

    fun FilenameMetadata.toParsedMedia(): ParsedMedia = ParsedMedia(
        title = title?.value,
        normalizedTitle = normalizedTitle,
        alternativeTitle = alternativeTitle?.value,
        year = year?.value,
        season = season?.value,
        seasonEnd = seasonEnd,
        episode = episode?.value,
        episodeEnd = episodeEnd,
        absoluteEpisode = absoluteEpisode?.value,
        airDate = airDate?.value,
        resolution = resolution?.value,
        quality = quality?.value,
        qualityTags = qualityTags,
        streamingService = streamingService?.value,
        videoCodec = videoCodec?.value,
        bitDepth = bitDepth,
        hdr = hdr?.value,
        audioCodecs = audio?.codecs.orEmpty(),
        audioChannels = audio?.channels,
        audioBitrateKbps = audio?.bitrateKbps,
        isDualAudio = audio?.isDualAudio == true,
        isMultiAudio = audio?.isMultiAudio == true,
        languages = languages.map { it.value.code },
        isDubbed = isDubbed,
        subtitleLanguages = subtitles?.languages?.map { it.code }.orEmpty(),
        subtitlesHardcoded = subtitles?.hardcoded,
        edition = edition?.value,
        releaseGroup = releaseGroup?.value,
        partNumber = partNumber?.value,
        flags = flags.map { it.name }.toSet(),
        kind = kind.value.name,
        fieldConfidence = fieldConfidences(),
        confidence = confidence,
        warnings = warnings,
        parserVersion = parserVersion,
    )
}
