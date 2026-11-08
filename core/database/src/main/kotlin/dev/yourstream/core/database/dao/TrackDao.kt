package dev.yourstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.AudioTrackEntity
import dev.yourstream.core.database.entity.LanguageSettingsEntity
import dev.yourstream.core.database.entity.SubtitleTrackEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    // ---- subtitles ----

    @Query("SELECT * FROM subtitle_track WHERE media_id = :mediaId")
    suspend fun subtitleForFile(mediaId: Long): SubtitleTrackEntity?

    @Upsert
    suspend fun upsertSubtitle(track: SubtitleTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtitles(tracks: List<SubtitleTrackEntity>)

    @Query("DELETE FROM subtitle_track WHERE id = :id")
    suspend fun deleteSubtitle(id: Long)

    @Query("DELETE FROM subtitle_track WHERE media_id = :mediaId")
    suspend fun deleteSubtitleForFile(mediaId: Long)

    @Query("DELETE FROM subtitle_track WHERE for_media_id = :mediaId AND source = 'EMBEDDED'")
    suspend fun deleteEmbedded(mediaId: Long)

    /**
     * Subtitles for a playable unit: tracks attached to the episode (or the movie when
     * [episodeId] is null), plus series/movie-wide ones without an episode, plus embedded tracks
     * of [mediaId] when given.
     */
    @Query(
        """SELECT * FROM subtitle_track
        WHERE (item_id = :itemId AND (episode_id = :episodeId OR episode_id IS NULL OR :episodeId IS NULL))
           OR (:mediaId IS NOT NULL AND for_media_id = :mediaId)
        ORDER BY CASE source WHEN 'EMBEDDED' THEN 0 WHEN 'LOCAL' THEN 1 ELSE 2 END, language, id""",
    )
    fun subtitlesFor(itemId: Long, episodeId: Long?, mediaId: Long?): Flow<List<SubtitleTrackEntity>>

    @Query("SELECT media_id FROM subtitle_track WHERE item_id IS NULL AND media_id IS NOT NULL LIMIT :limit")
    suspend fun unattachedSubtitleFiles(limit: Int): List<Long>

    @Query("UPDATE subtitle_track SET item_id = :target WHERE item_id = :source")
    suspend fun moveSubtitles(source: Long, target: Long)

    @Query("UPDATE subtitle_track SET episode_id = :target WHERE episode_id = :source")
    suspend fun moveEpisodeSubtitles(source: Long, target: Long)

    // ---- audio ----

    @Query("SELECT * FROM audio_track WHERE media_id = :mediaId AND source = :source ORDER BY track_index")
    suspend fun audioTracks(mediaId: Long, source: String): List<AudioTrackEntity>

    @Query("SELECT DISTINCT source FROM audio_track WHERE media_id = :mediaId")
    suspend fun audioSources(mediaId: Long): List<String>

    @Query("DELETE FROM audio_track WHERE media_id = :mediaId AND source = :source")
    suspend fun deleteAudio(mediaId: Long, source: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudio(tracks: List<AudioTrackEntity>)

    @Query(
        """SELECT DISTINCT a.language FROM audio_track a JOIN media_link l ON l.media_id = a.media_id
        WHERE l.item_id = :itemId AND a.language IS NOT NULL ORDER BY a.language""",
    )
    suspend fun audioLanguagesOfItem(itemId: Long): List<String>

    // ---- preferences (user data) ----

    @Query("SELECT * FROM track_preference WHERE item_id = :itemId")
    suspend fun preference(itemId: Long): TrackPreferenceEntity?

    @Query("SELECT * FROM track_preference WHERE item_id = :itemId")
    fun observePreference(itemId: Long): Flow<TrackPreferenceEntity?>

    @Upsert
    suspend fun upsertPreference(preference: TrackPreferenceEntity)

    @Query("DELETE FROM track_preference WHERE item_id = :itemId")
    suspend fun deletePreference(itemId: Long)

    // ---- language settings (user data) ----

    @Query("SELECT * FROM language_settings WHERE id = 1")
    suspend fun languageSettings(): LanguageSettingsEntity?

    @Query("SELECT * FROM language_settings WHERE id = 1")
    fun observeLanguageSettings(): Flow<LanguageSettingsEntity?>

    @Upsert
    suspend fun putLanguageSettings(settings: LanguageSettingsEntity)
}
