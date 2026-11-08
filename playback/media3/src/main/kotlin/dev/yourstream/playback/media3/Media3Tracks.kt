package dev.yourstream.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.TrackCandidate
import dev.yourstream.playback.tracks.TrackOrigin
import dev.yourstream.playback.tracks.TrackSelection
import dev.yourstream.playback.tracks.TrackType
import dev.yourstream.playback.tracks.withLabelHints

/** Media3's track model ↔ the UI-independent [TrackCandidate]s, and applying a selection. */
@OptIn(UnstableApi::class)
object Media3Tracks {

    data class Mapped(val candidates: List<TrackCandidate>, val locations: Map<String, Pair<Tracks.Group, Int>>)

    fun candidates(tracks: Tracks, sideloaded: List<SideloadedSubtitle>): Mapped {
        // MergingMediaSource prefixes the ids of merged tracks ("1:ext:7"): match on the suffix.
        fun externalFor(formatId: String?) = formatId?.let { fid -> sideloaded.firstOrNull { fid == it.id || fid.endsWith(":" + it.id) } }
        val list = mutableListOf<TrackCandidate>()
        val where = HashMap<String, Pair<Tracks.Group, Int>>()
        var audioIndex = 0
        var textIndex = 0
        tracks.groups.forEachIndexed { g, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.SUBTITLE
                else -> return@forEachIndexed
            }
            for (t in 0 until group.length) {
                val f = group.getTrackFormat(t)
                val ext = externalFor(f.id)
                val id = ext?.id ?: "g$g:t$t"
                list += TrackCandidate(
                    id = id,
                    type = type,
                    index = if (type == TrackType.AUDIO) audioIndex++ else textIndex++,
                    declaredLanguage = f.language,
                    label = f.label ?: f.labels.firstOrNull()?.value,
                    codec = f.codecs ?: f.sampleMimeType,
                    channels = f.channelCount.takeIf { it != Format.NO_VALUE },
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    isHearingImpaired = f.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_CAPTION) != 0,
                    isCommentary = f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
                    isDescriptive = f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0,
                    origin = ext?.origin ?: TrackOrigin.EMBEDDED,
                    fileName = ext?.fileName,
                    contentLanguage = ext?.contentLanguage,
                    supported = group.isTrackSupported(t),
                ).withLabelHints()
                where[id] = group to t
            }
        }
        return Mapped(list, where)
    }

    /** Applies a selection: overrides for the chosen tracks; text disabled when no subtitle is chosen. */
    fun apply(player: ExoPlayer, mapped: Mapped, selection: TrackSelection) {
        val b = player.trackSelectionParameters.buildUpon()
        selection.audio.track?.let { a -> mapped.locations[a.id]?.let { (group, i) -> b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)) } }
        val sub = selection.subtitle.track
        if (sub == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else mapped.locations[sub.id]?.let { (group, i) ->
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
    }

    /** Before tracks are known: let ExoPlayer's own selector lean the same way (avoids a visible switch). */
    fun presetLanguages(player: ExoPlayer, prefs: LanguagePreferences) {
        val p = prefs.normalized()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*(p.audio + listOfNotNull(p.fallbackLanguage)).distinct().toTypedArray())
            .setPreferredTextLanguages(*p.subtitles.toTypedArray())
            .build()
    }
}
