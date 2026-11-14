package dev.yourstream.assistant.engine

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.search.core.SearchText

/** Writes library rows directly (as the library sync would), with prepared statements for bulk use. */
class AssistantFixture(db: YourStreamDatabase) {
    val sql: SupportSQLiteDatabase = db.openHelper.writableDatabase
    private var nextMedia = 1L
    private var nextSeason = 1L
    private var nextEpisode = 1L
    private val chats = HashSet<Long>()

    private val insItem = sql.compileStatement(
        """INSERT INTO library_item (id, kind, match_key, title, sort_title, normalized_title, year, search_text, title_confidence, locked, hidden,
            added_at, updated_at, available_count, latest_added_at, max_resolution, total_size, season_count, episode_count, watched_count, last_watched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.9, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
    )
    private val insMeta = sql.compileStatement(
        """INSERT INTO item_metadata (item_id, provider, external_id, imdb_id, title, original_title, overview, poster_url, backdrop_url, genres,
            release_date, year, original_language, runtime_minutes, rating, `cast`, directors, series_status, fetched_at)
            VALUES (?, ?, ?, NULL, ?, ?, NULL, NULL, NULL, ?, NULL, ?, ?, NULL, ?, '[]', '', NULL, 0)""",
    )
    private val insLang = sql.compileStatement("INSERT OR IGNORE INTO library_item_language (item_id, language) VALUES (?, ?)")
    private val insSeason = sql.compileStatement("INSERT INTO season (id, series_id, number, title, episode_count) VALUES (?, ?, ?, NULL, 0)")
    private val insEpisode = sql.compileStatement(
        "INSERT INTO episode (id, series_id, season_id, season_number, number, number_end, absolute_number, title, air_date, available_count, latest_added_at) VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, 1, 0)",
    )
    private val insMedia = sql.compileStatement(
        """INSERT INTO media (id, chat_id, message_id, album_id, message_date, edit_date, file_id, file_unique_id, remote_file_id, file_name, mime_type,
            extension, size_bytes, duration_seconds, width, height, video_codec, audio_codecs, supports_streaming, media_type, content_type, caption,
            audio_title, audio_performer, download_state, downloaded_bytes, local_path, search_title, fingerprint, canonical_media_id, duplicate_kind,
            is_deleted, deleted_at, indexed_at, updated_at, last_seen_at)
            VALUES (?, ?, ?, 0, 0, 0, 1, ?, 'r', ?, 'video/x-matroska', 'mkv', 700000000, NULL, NULL, NULL, NULL, '', 1, 'VIDEO', 'DOCUMENT', '',
            NULL, NULL, 'NOT_DOWNLOADED', 0, NULL, '', NULL, NULL, NULL, 0, NULL, 0, 0, 0)""",
    )
    private val insParsed = sql.compileStatement(
        "INSERT INTO media_parsed (media_id, title, resolution, source, audio_codecs, languages, subtitle_languages, confidence, parser_version) VALUES (?, NULL, ?, ?, '', '', '', 0.9, 9)",
    )
    private val insLink = sql.compileStatement("INSERT INTO media_link (media_id, item_id, episode_id, method, signature, linked_at) VALUES (?, ?, ?, 'AUTO', '', 0)")

    fun chat(id: Long, title: String) {
        sql.execSQL("INSERT OR REPLACE INTO indexed_chat VALUES (?, ?, 'CHANNEL', 1, 'IDLE', NULL, NULL, NULL, 0, 0, 0, 0, 'TELEGRAM')", arrayOf<Any?>(id, title))
        chats += id
    }

    fun item(
        id: Long, title: String, kind: String = "MOVIE", year: Int? = null, available: Int = 1, hidden: Boolean = false, addedAt: Long = id,
        episodes: Int = 0, watchedEpisodes: Int = 0, totalSize: Long = id * 1000, lastWatchedAt: Long? = null,
    ) {
        val key = SearchText.normalize(title)
        insItem.clearBindings()
        listOf<Any?>(id, kind, "$kind:$key:$year:$id", title, key, key, year, key, if (hidden) 1 else 0, addedAt, addedAt, available, addedAt, null,
            totalSize, 0, episodes, watchedEpisodes, lastWatchedAt).forEachIndexed { i, v -> bind(insItem, i + 1, v) }
        insItem.executeInsert()
    }

    fun metadata(id: Long, title: String, genres: List<String> = emptyList(), provider: String = "tmdb", originalTitle: String? = null, originalLanguage: String? = null, rating: Double? = null, year: Int? = null) {
        insMeta.clearBindings()
        listOf<Any?>(id, provider, "x$id", title, originalTitle, genres.joinToString(","), year, originalLanguage, rating).forEachIndexed { i, v -> bind(insMeta, i + 1, v) }
        insMeta.executeInsert()
    }

    fun language(id: Long, vararg codes: String) = codes.forEach { c -> insLang.clearBindings(); insLang.bindLong(1, id); insLang.bindString(2, c); insLang.executeInsert() }

    /** Adds a season; returns its id. */
    fun season(seriesId: Long, number: Int): Long {
        val sid = nextSeason++
        insSeason.clearBindings(); insSeason.bindLong(1, sid); insSeason.bindLong(2, seriesId); insSeason.bindLong(3, number.toLong()); insSeason.executeInsert()
        return sid
    }

    fun episode(seriesId: Long, seasonId: Long, season: Int, number: Int): Long {
        val eid = nextEpisode++
        insEpisode.clearBindings()
        listOf<Any?>(eid, seriesId, seasonId, season, number).forEachIndexed { i, v -> bind(insEpisode, i + 1, v) }
        insEpisode.executeInsert()
        return eid
    }

    fun file(itemId: Long, fileName: String, chatId: Long = 1, resolution: Int? = 1080, quality: String? = "WEB-DL", episodeId: Long? = null) {
        if (chatId !in chats) chat(chatId, "Chat $chatId")
        val mid = nextMedia++
        insMedia.clearBindings()
        listOf<Any?>(mid, chatId, mid, "u$mid", fileName).forEachIndexed { i, v -> bind(insMedia, i + 1, v) }
        insMedia.executeInsert()
        insParsed.clearBindings()
        listOf<Any?>(mid, resolution, quality).forEachIndexed { i, v -> bind(insParsed, i + 1, v) }
        insParsed.executeInsert()
        insLink.clearBindings()
        listOf<Any?>(mid, itemId, episodeId).forEachIndexed { i, v -> bind(insLink, i + 1, v) }
        insLink.executeInsert()
    }

    fun watch(itemId: Long, positionMs: Long, durationMs: Long = 100_000, completed: Boolean = false, at: Long = 1_000) =
        sql.execSQL("INSERT OR REPLACE INTO watch_state VALUES (?, 0, ?, ?, ?, NULL, 1, ?, NULL)", arrayOf<Any?>(itemId, positionMs, durationMs, if (completed) 1 else 0, at))

    fun watchEpisode(seriesId: Long, episodeId: Long, positionMs: Long = 100_000, durationMs: Long = 100_000, completed: Boolean = true, at: Long = 1_000) =
        sql.execSQL("INSERT OR REPLACE INTO watch_state VALUES (?, ?, ?, ?, ?, NULL, 1, ?, NULL)", arrayOf<Any?>(seriesId, episodeId, positionMs, durationMs, if (completed) 1 else 0, at))

    fun transaction(block: AssistantFixture.() -> Unit) {
        sql.beginTransaction()
        try { block(); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
    }

    private fun bind(s: androidx.sqlite.db.SupportSQLiteStatement, i: Int, v: Any?) = when (v) {
        null -> s.bindNull(i)
        is Long -> s.bindLong(i, v)
        is Int -> s.bindLong(i, v.toLong())
        is Double -> s.bindDouble(i, v)
        is Boolean -> s.bindLong(i, if (v) 1 else 0)
        else -> s.bindString(i, v.toString())
    }
}
