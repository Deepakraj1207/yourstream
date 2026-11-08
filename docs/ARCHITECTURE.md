# YourStream — Architecture & Roadmap (Stage 0 blueprint)

Status: **Phases 1–3 implemented** (Telegram layer; media discovery and indexing; filename parsing). This document is the reference
for every later phase. Decisions marked **DECISION** still need sign-off; until then the
recommended defaults are used (minSdk 26, `dev.yourstream`, `tdl-coroutines`).

### Implementation status

| Phase | State | Notes |
|---|---|---|
| P0 Foundation | partial | Version catalog, Gradle 9.8.1 wrapper, AGP 9.4.1, Kotlin 2.4.21, Hilt. `build-logic` convention plugins and CI are not added yet |
| P1 Telegram core | **done** | `:core:common`, `:telegram:api`, `:telegram:core`, `:telegram:tdlib`, debug UI in `:app`. See §14 |
| P2 (= roadmap P3 + basic P4) Indexing | **done** | `:core:database`, `:media:api`, `:media:parser`, `:media:indexer`. See [INDEXING.md](INDEXING.md) |
| P3 (= roadmap P4) Filename parsing | **done** | Standalone `:media:filename`, DB v2, re-parse worker. See [PARSER.md](PARSER.md) |

---

## 1. Reference project review: TMPlayer (dracu-lah/TMPlayer)

### 1.1 What it is

| Aspect | TMPlayer |
|---|---|
| License | **GPL-3.0-or-later** with an OpenSSL linking exception |
| Modules | `:app` (Android), `:core` (Kotlin Multiplatform: JVM, Android, JS), `:ui`, `:desktop` (Compose Desktop + libmpv), plus `webapp`, `site`, `workers` |
| Telegram | TDLib through `dev.g000sha256:tdl-coroutines` (a Kotlin coroutine wrapper with prebuilt natives) |
| Player | Media3 / ExoPlayer 1.11 + `nextlib` FFmpeg renderers (Android); libmpv (desktop) |
| Persistence | **DataStore Preferences only**: no Room, no SQL. Watch state, bookmarks and watch-later are key-value documents |
| DI | None. Objects are wired by hand |
| Metadata | TMDB, TVmaze, OMDb, Wikidata, AniList, plus an intro-skip times API, with a confidence score |
| SDK | minSdk 23, targetSdk 35, compileSdk 37, AGP 9.4, Kotlin 2.4 |
| Tests | Many JVM unit tests on pure logic, plus Robolectric/Compose screenshot tests |

### 1.2 What is worth learning

1. **Streaming a TDLib file into Media3 (`TdDataSource` + `TdByteWindow` + `DownloadWindow`).**
   TDLib downloads one contiguous range `[offset, offset + prefix)`. The player reads through a
   custom `DataSource`. When a seek lands outside that range, the download is restarted at the
   new offset ("re-aiming") instead of waiting for it to catch up. The range arithmetic is a
   **pure function with unit tests**. We copy this *idea*, not the code.
2. **Stream warm-up.** Before playback it fetches the MP4 `moov` atom or the MKV `Cues` (often at
   the end of the file), so the first frame and seeking don't stall. It also handles MKV files
   that have no cues.
3. **Telling Media3 the container type.** A `tdfile://id` URI has no file extension, so the source
   passes the MIME type (inferred from the file name when needed). Without it Media3 probes every
   extractor in turn.
4. **Small reads served from memory.** Media3's Matroska reader asks for 1–2 bytes at a time while
   it walks the index, so the source reads ahead and answers those reads from a buffer.
5. **Split files** (`.001`/`.002`, `part1`/`part2`) joined into one logical stream
   (`JoinedDataSource`, `SplitPieces`).
6. **Pure, testable core.** Name parsing (`MediaName`), match confidence, sorting and
   seek arithmetic are plain Kotlin with JVM tests.
7. **Telegram quirks** it already handles: `loadChats` returning 404 means "no more chats", not an
   error; search filters (Video/Document/Animation); forum topics; heap limits on 1 GB TV sticks.
8. **Real-device problems**: Dolby Vision base layer, HDR10+ masking, frame-rate matching, audio
   passthrough buffer caps, broken hardware decoders. Use these as a **test checklist** for our
   Playback phase.

### 1.3 What we should NOT copy

| TMPlayer choice | Why not | Our approach |
|---|---|---|
| Copying code at all | GPL-3.0: any copied code makes our project GPL (see §7) | Clean-room re-implementation of the ideas, unless we choose GPL too |
| DataStore as the only database | No relations, no queries, no full-text search (FTS), no migrations. It can't hold a real media library | **Room** with a schema, FTS, foreign keys and migration tests |
| No dependency injection, hand wiring | Hard to swap providers or test with fakes | **Hilt** plus interfaces at every boundary |
| A 9,000-line `PlayerActivity` | UI, playback logic and policy all in one class | Playback engine in its own module with no UI; the UI only observes state |
| TDLib types leaking into data and UI code (`dev.g000sha256.tdl.dto.*` everywhere) | Locks the whole app to one wrapper library | Our own `TelegramClient` interface plus our own models. Only `:telegram:tdlib` sees TDLib |
| Metadata built into the "data" package | No catalog/metadata/source separation | Separate `catalog`, `metadata`, `source` and `playback` concepts, each with its own interfaces |
| Library = the video list of a chat | Organised around chats, not titles | Organised around titles: Work → Season → Episode → **Versions** (files), with chats only as *sources* |
| KMP, desktop, web app and TV channels all at once | Too much scope for a new project | Android only. Keep domain modules pure Kotlin so KMP stays possible later |
| UI polish mixed into core logic | Conflicts with our Stage 1 / Stage 2 rule | Presentation-agnostic state holders; throwaway debug UI in Stage 1 |

---

## 2. Core concepts (kept strictly separate)

| Concept | Question it answers | Example | Must never do |
|---|---|---|---|
| **Catalog** | "What titles exist / are interesting?" | TMDB Trending, "Malayalam movies 2025", Cinemeta Top | Return playable URLs |
| **Metadata** | "What is this title?" (overview, cast, artwork, episodes, IDs) | TMDB details, Cinemeta meta | Decide what is playable |
| **Media Source** | "Where are the bytes of a file the user can access?" | A Telegram message document, a local file | Know about catalogs |
| **Library** | "Which titles does the user *have*, in which versions?" | Work + matched source files | Fetch bytes |
| **Playback** | "Play this playable source well" | Media3 player with a Telegram DataSource | Look up sources by itself |

A catalog item becomes playable **only** when the user's library already holds a matched source
for it. The link is `CatalogItem → ExternalIds → MediaItem → MediaMatch → SourceFile`. The
`CatalogProvider` interface has **no stream or URL field**. If a third-party catalog (for example
a Stremio-protocol add-on) also offers `stream` resources, we ignore them by design.

---

## 3. Architecture diagram

```
                           ┌────────────────────────────────────────────┐
                           │                    :app                     │
                           │  Hilt graph · navigation · Stage-1 debug UI │
                           │  (Stage 2: :feature:* + :core:designsystem) │
                           └───────────────┬────────────────────────────┘
                                           │ observes StateFlow / calls use cases
 ┌──────────────┬──────────────┬───────────┼───────────┬──────────────┬───────────────┐
 ▼              ▼              ▼           ▼           ▼              ▼               ▼
:search     :history     :intelligence  :library   :playback      :catalog:api   :telegram:auth
 │  FTS +      │ progress,   │ next-up,      │ works,     │ engine,       │ CatalogProvider
 │  ranking    │ continue    │ version pick, │ seasons,   │ DataSource,   │ registry
 │             │ watching    │ skip, new-ep  │ versions   │ tracks, svc   ├─ :catalog:tmdb
 │             │             │               │            │               ├─ :catalog:cinemeta
 │             │             │               │            │               ├─ :catalog:indiastreams
 │             │             │               │            │               ├─ :catalog:regional
 │             │             │               │            │               └─ :catalog:custom
 └──────┬──────┴──────┬──────┴───────┬───────┴─────┬──────┘
        │             │              │             │
        ▼             ▼              ▼             ▼
 :metadata:matcher ◀── :media:parser   :media:indexer ──────────▶ :media:source
   │ scores candidates   (filename /     │ chat scan, incremental   │ MediaSourceResolver
   │ from MetadataProvider caption)      │ sync (WorkManager)       │ → PlayableSource
   ▼                                     ▼                          ▼
 :metadata:api ◀── implemented by :catalog:tmdb, :catalog:cinemeta  :telegram:api (interfaces)
                                                                     ▲
 :core:database (Room) ◀── every repository writes here              │ implemented by
 :core:datastore (settings)                                          :telegram:tdlib (TDLib JNI)
 :core:network (Ktor + cache + rate limit)
 :core:model · :core:common · :core:testing
```

Data flow for the main path:

```
Telegram chat ──TDLib──▶ TelegramMediaFile ──indexer──▶ source_file (Room)
     ──parser──▶ parsed_release ──matcher+MetadataProvider──▶ media_match → media_item/episode
     ──library──▶ Work with versions ──user taps play──▶ MediaSourceResolver
     ──▶ PlayableSource(tdfile://…) ──▶ PlaybackEngine (Media3 + TelegramDataSource)
     ──progress events──▶ history ──▶ intelligence (next up, resume, prefs)
```

Dependency rules (enforced by module boundaries):
- `*:api` modules are **pure Kotlin/JVM**. They have no Android imports and no vendor SDK imports.
- Feature/domain modules depend only on `:api` modules plus `:core:*`. Implementations are bound in Hilt modules.
- Only `:telegram:tdlib` imports TDLib. Only `:playback` imports Media3. Only `:catalog:*` know provider HTTP formats.
- UI code (debug or final) never touches Room, TDLib or Media3 directly.

---

## 4. Platform targets

| Setting | Value | Reason |
|---|---|---|
| **minSdk** | **26** (Android 8.0) | `java.time` and modern MediaCodec behaviour without desugaring; covers nearly all active phones and Android TV / Google TV devices. Cost: legacy Fire TV Sticks on Fire OS 6 (API 25) are excluded. If those matter we can drop to 24 with core-library desugaring. **DECISION** |
| **targetSdk** | **36** | Google Play requires 36 for new apps and updates from Aug 2026 |
| **compileSdk** | **37** | Current AndroidX and the TDLib wrapper need it (TMPlayer is on 37 for the same reason) |
| ABIs | arm64-v8a, armeabi-v7a (TV sticks), x86_64 (emulator) | TDLib native code is ~20–30 MB per ABI → ABI splits / App Bundle |
| Native page size | 16 KB-aligned `.so` files | Play requirement for apps targeting 35+; TDLib must be built or verified for it |
| Form factors | Phone first, Android TV supported from the same APK (`leanback` feature `required=false`) | TV UI arrives in Stage 2 |
| JDK / Gradle | JDK 21, Gradle Kotlin DSL, version catalog, `build-logic` convention plugins | |

Exact library versions are pinned in `gradle/libs.versions.toml` during Phase 0, against the
latest stable releases on that day. TMPlayer's known-good set (Kotlin 2.4.x, AGP 9.x,
Media3 1.11.x) is the starting point.

---

## 5. Module / package structure

Root package: `dev.yourstream` (**DECISION**: final app ID).

```
yourstream/
├── build-logic/convention/        # android-library, jvm-library, hilt, room, test plugins
├── app/                           # Application, Hilt entry, nav host, debug screens (Stage 1)
├── core/
│   ├── common/        (JVM)       # AppResult, DispatcherProvider, Clock, Logger, ids
│   ├── model/         (JVM)       # pure domain models shared by all layers
│   ├── database/      (Android)   # Room DB, entities, DAOs, migrations, FTS
│   ├── datastore/     (Android)   # typed settings (Proto/Preferences DataStore)
│   ├── network/       (JVM)       # Ktor HttpClient factory, HTTP cache, RateLimiter, retry
│   └── testing/       (JVM)       # fakes, fixtures, coroutine test rules
├── telegram/
│   ├── api/           (JVM)       # public contracts: TelegramManager, TelegramAuthManager, repositories, models
│   ├── core/          (JVM)       # TelegramClient port + implementations of the contracts (unit-tested)
│   └── tdlib/         (Android)   # TdlibTelegramClient (the only TDLib user), mappers, network monitor, Hilt
├── media/
│   ├── source/        (JVM)       # MediaSource, MediaSourceResolver, PlayableSource
│   ├── filename/      (JVM)       # standalone MediaFilenameParser (no dependencies)
│   ├── parser/        (JVM)       # classifier, normaliser, filename → ParsedMedia bridge
│   └── indexer/       (Android)   # IndexingPipeline, ChatIndexer, WorkManager workers
├── metadata/
│   ├── api/           (JVM)       # MetadataProvider, ExternalIds, MetadataDetails
│   └── matcher/       (JVM)       # MatchEngine, scoring, confidence thresholds
├── catalog/
│   ├── api/           (JVM)       # CatalogProvider, CatalogRegistry, CatalogItem
│   ├── tmdb/          (JVM)       # TMDB: CatalogProvider + MetadataProvider
│   ├── cinemeta/      (JVM)       # Cinemeta: CatalogProvider + MetadataProvider
│   ├── indiastreams/  (JVM)       # catalog/meta resources only, streams ignored
│   ├── regional/      (JVM)       # curated Indian-language catalogs built on TMDB discover
│   └── custom/        (JVM)       # user-added Stremio-protocol catalog/meta add-ons
├── library/           (Android)   # LibraryRepository: works, seasons, episodes, versions
├── playback/          (Android)   # PlaybackEngine, TelegramDataSource, tracks, MediaSessionService
├── search/            (Android)   # SearchRepository over FTS + normalisation + remote fallback
├── history/           (Android)   # WatchHistoryRepository, progress tracking
├── intelligence/      (JVM)       # policies: NextUp, VersionSelector, SkipSegments, NewEpisodes
└── feature/           # Stage 2 only: home, details, player-ui, search-ui, settings, tv …
```

Inside each module: `api/` (public interfaces), `internal/` (implementations, `internal`
visibility), `di/` (Hilt module), `model/`.

~25 modules is deliberate. Convention plugins keep each `build.gradle.kts` to a few lines, and
Gradle configuration cache plus parallel builds keep build times reasonable. Modules can be
merged if builds get slow.

---

## 6. Database strategy

**One Room database** (`YourStreamDatabase`) in `:core:database`. Settings live in DataStore,
not Room. Artwork goes to Coil's disk cache. Media bytes stay in TDLib's own file cache.

### 6.1 Two kinds of data

| Kind | Tables | Rule |
|---|---|---|
| **Rebuildable cache** | source_file, parsed_release, metadata_cache, catalog_cache, search_fts, media_item/episode (auto) | Can be wiped and rebuilt by re-indexing; destructive migration acceptable as a last resort |
| **User data** | watch_progress, watch_event, media_match (manual/locked), track_preference, user_collection, indexed_chat settings | Never dropped; every schema change ships a tested `Migration`; exportable to JSON backup |

### 6.2 Core tables

```
indexed_chat        chat_id PK, title, type, enabled, last_indexed_message_id,
                    oldest_indexed_message_id, full_scan_done, updated_at
source_file         id PK, source_type(TELEGRAM|LOCAL), chat_id, message_id, remote_unique_id UNIQUE,
                    file_name, caption, mime_type, size_bytes, duration_ms, width, height,
                    supports_streaming, album_id, posted_at, split_group_id, split_index, deleted
parsed_release      source_file_id PK/FK, title, alt_titles, year, season, episode(s), absolute_ep,
                    resolution, source(WEB-DL/HDRip…), codec, hdr, audio_codecs, audio_langs,
                    subtitle_langs, edition, parser_version, confidence
media_item          id PK, kind(MOVIE|SERIES|EPISODE), title, original_title, year, overview,
                    poster_url, backdrop_url, runtime, genres, original_language, metadata_provider
external_id         media_item_id FK, provider(TMDB|IMDB|TVDB|CINEMETA|…), value  UNIQUE(provider,value)
season / episode    series_id FK, season_no, episode_no, title, air_date, still_url
media_match         source_file_id FK UNIQUE, media_item_id FK, episode_id FK?, confidence,
                    method(AUTO|MANUAL), locked, matched_at
metadata_cache      provider, request_key, json, fetched_at, ttl  PK(provider, request_key)
catalog_cache       provider, catalog_id, page, json, fetched_at
watch_progress      PK(media_item_id or episode_id), last_source_file_id, position_ms, duration_ms,
                    completed, updated_at
watch_event         id, media_ref, source_file_id, started_at, ended_at, position_ms   (history log)
track_preference    scope(GLOBAL|SERIES|ITEM), scope_id, audio_lang, subtitle_lang, subtitle_offset_ms
external_subtitle   id, media_ref, path, lang, format, origin
search_fts          FTS4 table (title, alt_titles, original_title, file_name, caption, cast_names)
```

### 6.3 Key rules

- **Never persist TDLib `file.id` as a key.** It is local to the TDLib session and changes after a
  logout or reinstall. Store `remote_unique_id` plus `(chat_id, message_id)`, and resolve a
  fresh file ID at play time.
- **Files are not titles.** One work can have many versions (720p/1080p, Tamil/Hindi dubs, split
  parts). The library groups them through `media_match`.
- **Manual matches win.** `locked = 1` means re-matching never overwrites the user's choice.
- `parser_version` lets us re-parse only the rows an older parser version produced.
- FTS4 (Room supports FTS3/4; the Android framework SQLite has no guaranteed FTS5). Text is
  normalised before insert: NFKD, case-folded, diacritics stripped, plus transliterated aliases.
- `exportSchema = true`. Schemas are committed in `core/database/schemas`. `MigrationTestHelper`
  tests every version step in CI.
- Large reads use Paging 3 with `PagingSource` straight from DAOs. Reactive reads return `Flow`.
- Writes from the indexer go in batched transactions (e.g. 200 rows per transaction).

---

## 7. Dependencies & licensing

### 7.1 Project license — **DECISION**

| Option | Consequence |
|---|---|
| **GPL-3.0-or-later** (recommended) | We may study *and* reuse TMPlayer code with attribution, and ship GPL FFmpeg builds (`nextlib`). Forks must stay open source |
| Apache-2.0 / MIT | Must be a strict **clean-room** implementation (no TMPlayer code copied); only LGPL FFmpeg builds (Media3 FFmpeg extension built without GPL parts) |

Either way we re-implement rather than copy-paste, because TMPlayer is organised differently.

### 7.2 Dependency table

| Dependency | License | Notes |
|---|---|---|
| TDLib | BSL-1.0 | Bundles OpenSSL (Apache-2.0 for v3) and zlib. Build ourselves (official JNI) or use a wrapper |
| tdl-coroutines (optional wrapper) | verify (Apache-2.0 expected) | Prebuilt natives; only `:telegram:tdlib` may depend on it, so it can be swapped |
| AndroidX Media3 / ExoPlayer | Apache-2.0 | |
| Media3 FFmpeg extension / nextlib | LGPL-2.1+ or GPL-3 depending on build | Needed for AC3/EAC3/DTS/TrueHD audio on devices without those decoders |
| Room, WorkManager, DataStore, Paging, Lifecycle, Compose | Apache-2.0 | |
| Hilt / Dagger | Apache-2.0 | KSP, not kapt |
| Ktor client (OkHttp engine) | Apache-2.0 | |
| kotlinx.serialization / coroutines | Apache-2.0 | |
| Coil 3 | Apache-2.0 | artwork |
| ICU4J / Android `Transliterator` (API 29+) | Unicode license | Indic transliteration for search; fall back to our own tables below API 29 |

### 7.3 Service terms (not code licenses, but binding)

- **Telegram API**: each build needs its own `api_id`/`api_hash` from my.telegram.org. Keep them
  in `local.properties` / CI secrets, never in git. Respect FLOOD_WAIT limits. Follow the
  Telegram API Terms of Service (no spam or scraping of other users' data).
- **TMDB**: free API key; requires the "This product uses the TMDB API but is not endorsed or
  certified by TMDB" notice and logo in About; non-commercial terms; rate limits. Users may
  supply their own key.
- **Cinemeta / Stremio add-ons**: public endpoints without a formal SLA or license. Treat as
  best-effort and cache aggressively.
- **IndiaStreams / other add-ons**: check each provider's terms before shipping. We use only
  `catalog` and `meta` resources.
- **OpenSubtitles (later)**: requires an API key and user login for downloads.
- **Copyright**: the app plays media the user can access in their own Telegram account. It has
  no built-in channel directory, does not discover or promote third-party channels, and catalogs
  never produce streams.

---

## 8. Major interfaces (signatures, pure Kotlin)

```kotlin
// ---------- :core:common ----------
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}
sealed interface AppError {
    data class Network(val cause: Throwable?) : AppError
    data class RateLimited(val retryAfterMs: Long) : AppError
    data class Telegram(val code: Int, val message: String) : AppError
    data object NotFound : AppError
    data class Unknown(val cause: Throwable?) : AppError
}

// ---------- :telegram:api ----------
interface TelegramAuth {
    val state: StateFlow<AuthState>            // WaitPhone, WaitCode, WaitPassword, WaitQr(link), Ready, LoggedOut
    suspend fun submitPhone(phone: String): AppResult<Unit>
    suspend fun submitCode(code: String): AppResult<Unit>
    suspend fun submitPassword(password: String): AppResult<Unit>
    suspend fun requestQrLogin(): AppResult<Unit>
    suspend fun logOut(): AppResult<Unit>
}

interface TelegramChats {
    fun chats(): Flow<List<TgChat>>
    suspend fun chat(chatId: Long): AppResult<TgChat>
}

interface TelegramMessages {
    /** Media messages (video, video-documents, audio) newest → oldest, paged by message id. */
    suspend fun mediaPage(chatId: Long, fromMessageId: Long, limit: Int, filter: TgMediaFilter): AppResult<TgPage>
    suspend fun message(chatId: Long, messageId: Long): AppResult<TgMediaMessage?>
    /** New/edited/deleted media messages in enabled chats. */
    fun mediaUpdates(): Flow<TgMediaUpdate>
    suspend fun searchMedia(query: String, chatId: Long?, limit: Int): AppResult<TgPage>
}

interface TelegramFiles {
    suspend fun resolve(chatId: Long, messageId: Long): AppResult<TgFileHandle>   // fresh session file id
    fun fileState(handle: TgFileHandle): Flow<TgFileState>                        // offset, prefix, size, completed
    suspend fun requestRange(handle: TgFileHandle, offset: Long, priority: Int): AppResult<Unit>
    suspend fun readAvailable(handle: TgFileHandle, offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int
    suspend fun cancel(handle: TgFileHandle)
    suspend fun thumbnail(chatId: Long, messageId: Long): AppResult<String?>      // local path
}

// ---------- :media:source ----------
interface MediaSource {
    val type: SourceType                                     // TELEGRAM, LOCAL
    suspend fun toPlayable(file: SourceFileRef): AppResult<PlayableSource>
    suspend fun isAvailable(file: SourceFileRef): Boolean
}
interface MediaSourceResolver {                             // picks the MediaSource by type
    suspend fun resolve(file: SourceFileRef): AppResult<PlayableSource>
}
data class PlayableSource(
    val uri: String,                                         // tdfile://… or file://…
    val mimeType: String?,
    val parts: List<String> = emptyList(),                   // split files joined in order
    val sizeBytes: Long?,
    val externalSubtitles: List<SubtitleRef> = emptyList(),
)

// ---------- :media:parser ----------
interface ReleaseNameParser {
    val version: Int
    fun parse(fileName: String, caption: String?, chatTitle: String?): ParsedRelease
}

// ---------- :media:indexer ----------
interface MediaIndexer {
    val progress: StateFlow<Map<Long, IndexProgress>>        // per chat
    suspend fun enableChat(chatId: Long)
    suspend fun disableChat(chatId: Long, purge: Boolean)
    suspend fun indexChat(chatId: Long, mode: IndexMode)     // FULL, INCREMENTAL
    fun schedulePeriodicSync()
}

// ---------- :metadata:api ----------
interface MetadataProvider {
    val id: ProviderId
    val capabilities: Set<MetadataCapability>                // MOVIES, SERIES, EPISODES, ANIME, REGIONAL_TITLES
    suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>>
    suspend fun details(ref: ExternalRef): AppResult<MetadataDetails>
    suspend fun episodes(ref: ExternalRef, season: Int): AppResult<List<EpisodeDetails>>
    suspend fun findByExternalId(id: ExternalRef): AppResult<MetadataCandidate?>  // imdb → tmdb etc.
}

// ---------- :metadata:matcher ----------
interface MatchEngine {
    suspend fun match(release: ParsedRelease, hints: MatchHints): MatchResult  // best + alternatives + confidence
    suspend fun rematch(sourceFileIds: List<Long>)
    suspend fun setManualMatch(sourceFileId: Long, ref: ExternalRef, episode: EpisodeRef?)
}

// ---------- :catalog:api ----------
interface CatalogProvider {
    val id: ProviderId
    val displayName: String
    suspend fun catalogs(): AppResult<List<CatalogDescriptor>>          // id, name, type, supported filters
    suspend fun page(catalogId: String, filters: CatalogFilters, page: Int): AppResult<CatalogPage>
    suspend fun search(query: String, type: MediaKind?): AppResult<List<CatalogItem>>
}
data class CatalogItem(                                     // NOTE: no stream / url field, by design
    val ref: ExternalRef, val kind: MediaKind, val title: String, val year: Int?,
    val posterUrl: String?, val originalLanguage: String?, val provider: ProviderId,
)
interface CatalogRegistry {
    val providers: StateFlow<List<CatalogProvider>>          // built-ins + user-added custom
    suspend fun addCustom(manifestUrl: String): AppResult<ProviderId>
    suspend fun remove(id: ProviderId)
}

// ---------- :library ----------
interface LibraryRepository {
    fun works(filter: LibraryFilter): Flow<PagingData<WorkSummary>>
    fun work(id: Long): Flow<WorkDetail?>                   // seasons, episodes, versions
    fun versions(media: MediaRef): Flow<List<SourceVersion>>
    fun availability(refs: List<ExternalRef>): Flow<Map<ExternalRef, Availability>>   // catalog ↔ library link
}

// ---------- :playback ----------
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>                      // idle/buffering/playing, position, buffered, tracks, error
    suspend fun open(request: PlayRequest)                   // media ref + chosen version + start position
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun selectAudio(track: TrackId?); fun selectSubtitle(track: TrackId?)
    fun setSubtitleOffset(ms: Long); fun setSpeed(speed: Float)
    suspend fun addExternalSubtitle(ref: SubtitleRef)
    fun release()
}
interface BufferPolicy {                                    // swappable buffering strategy
    fun loadControlFor(source: PlayableSource, network: NetworkKind, device: DeviceClass): BufferConfig
}

// ---------- :search ----------
interface SearchRepository {
    fun search(query: SearchQuery): Flow<SearchResults>      // library hits, catalog hits, telegram hits (sectioned)
    suspend fun suggestions(prefix: String): List<String>
    fun recent(): Flow<List<String>>
}

// ---------- :history ----------
interface WatchHistoryRepository {
    suspend fun recordProgress(media: MediaRef, source: Long, positionMs: Long, durationMs: Long)
    fun continueWatching(limit: Int): Flow<List<ContinueItem>>
    fun progress(media: MediaRef): Flow<WatchProgress?>
    suspend fun markWatched(media: MediaRef, watched: Boolean)
    fun history(): Flow<PagingData<WatchEvent>>
}

// ---------- :intelligence ----------
interface NextUpPolicy { suspend fun nextFor(media: MediaRef): MediaRef? }
interface VersionSelector { fun pick(versions: List<SourceVersion>, ctx: SelectionContext): SourceVersion? }
interface TrackPreferencePolicy { suspend fun preferredTracks(media: MediaRef, available: TrackSet): TrackChoice }
interface SkipSegmentProvider { suspend fun segments(media: MediaRef, durationMs: Long): List<SkipSegment> } // chapters/intro
interface NewEpisodeDetector { fun newEpisodes(): Flow<List<NewEpisode>> }
```

How these fit:
- `:catalog:tmdb` binds both `CatalogProvider` and `MetadataProvider` (`@IntoSet` multibindings),
  but they stay separate interfaces.
- `TelegramDataSource` (Media3, in `:playback`) depends only on `TelegramFiles`. It never sees TDLib.
- Every interface gets a fake in `:core:testing`.

---

## 9. Playback design highlights (where we beat TMPlayer on structure)

- `TelegramDataSource` + a pure `ByteWindow` model: re-aims on seek, warms up the head and tail
  (moov/Cues), reads ahead for small reads, enforces read timeouts, and passes the MIME type.
- `JoinedDataSource` for split parts. The split grouping is decided at **index** time, not play time.
- Buffering: `BufferPolicy` derives min/max buffer from bitrate (`size / duration`), network
  type, and device RAM class. TDLib download priority is raised as the buffer drains.
- `VersionSelector`: picks the best version for device decode capability (HEVC/AV1/DV support,
  probed once via `MediaCodecList`), network, and the user's preferred audio language.
- `TrackPreferencePolicy`: audio/subtitle language remembered per series, falling back to the
  global setting. Subtitle offset is remembered per item.
- FFmpeg audio renderer as a fallback, enabled when the device lacks AC3/EAC3/DTS.
- `MediaSessionService` for background audio, Bluetooth controls, PiP and the lock screen.
- Progress is written every 10 s and on pause/stop. Completed = >90 % or credits reached.

---

## 10. Development roadmap

### Stage 1 — Functionality only (debug screens: plain Compose, default Material, no theming)

| Phase | Goal | Exit criteria |
|---|---|---|
| **P0 Foundation** | Repo, Gradle KTS + version catalog + `build-logic`, module skeletons, Hilt, logging, secrets (`api_id`, TMDB key), CI (build, unit tests, lint, detekt/ktlint), license | `./gradlew check` green in CI; empty app launches |
| **P1 Telegram core** | TDLib integration behind `:telegram:api`: auth (phone, code, 2FA, QR), chat list, media message paging, file info, updates | Debug screen: log in, list chats, list a chat's videos |
| **P2 Streaming vertical slice** (de-risk early) | `TelegramDataSource`, `ByteWindow`, warm-up, basic `PlaybackEngine`, debug player | Plays a 4 GB MKV from Telegram within a few seconds; seek anywhere < 3 s on good Wi-Fi |
| **P3 Indexing + DB** | Room schema v1, chat enable/disable, full + incremental indexing, WorkManager sync, split-file grouping, deletion handling | 10k-message channel indexed under FLOOD_WAIT limits; reindex is idempotent |
| **P4 Parsing** | `ReleaseNameParser` for filenames + captions, Indian release conventions (language tags, `ESub`, `HQ`, `PreDVD`, multi-audio), episode ranges | ≥ 95 % correct on a curated corpus of ≥ 500 real names |
| **P5 Metadata + matching** | `MetadataProvider` (TMDB first), `MatchEngine` scoring, confidence thresholds, manual override/lock, metadata cache with TTL | ≥ 90 % auto-match on corpus; manual fix persists through rematch |
| **P6 Catalogs** | `CatalogProvider` API, TMDB, Cinemeta, regional, IndiaStreams (catalog/meta only), custom add-on URL, availability linking | Browse catalogs in debug UI; "in your library" flag correct |
| **P7 Library** | Works/seasons/episodes/versions, dedupe, collections, sorting/filters, Paging | Series shows correct season/episode tree with multiple versions |
| **P8 Playback complete** | Track selection, audio/subtitle prefs, external subtitles (SRT/ASS/VTT), FFmpeg audio, `BufferPolicy`, MediaSession service, background/PiP, resume | Device-matrix checklist passes (see §11) |
| **P9 History** | Progress, continue watching, watched marks, history log, backup/export of user data | Survives app kill; JSON export → import round-trips |
| **P10 Search** | FTS with normalisation + transliteration + alias titles, ranking, filters, live Telegram search fallback, catalog search | Malayalam/Tamil/Hindi title typed in Latin or native script finds the item |
| **P11 Intelligence** | Next-up, auto version pick, skip intro/credits (chapters + heuristics), new-episode detection, "complete the season" | Policies unit-tested; next episode auto-queued |
| **P12 Hardening** | Storage management (TDLib cache limits, `optimizeStorage`), perf on 1–2 GB RAM TV, opt-in crash reporting, Stage 1 freeze | Stage 1 sign-off: every feature usable via debug UI |

### Stage 2 — UI/UX only (no core logic changes except bug fixes)

S2.1 Design system (`:core:designsystem`, tokens, typography) → S2.2 Phone UI (home, library,
details, search, settings) → S2.3 Android TV UI (Compose for TV, D-pad focus) → S2.4 Player UI
(gestures, scrubbing previews, track sheets) → S2.5 Accessibility, i18n, RTL → S2.6
Screenshot tests, polish, release (GitHub Releases + F-Droid; Play Store only after a policy review).

Why Stage 2 stays UI-only: every screen consumes a `StateFlow<UiState>` from a state holder that
already exists in Stage 1, so the debug screens are replaced, not rewritten underneath.

---

## 11. Testing strategy

| Layer | Tooling | What |
|---|---|---|
| Pure logic (parser, matcher, ByteWindow, policies) | JUnit5/kotlin.test, **golden corpus** files, property tests (Kotest property) | Most tests live here; fast, JVM only |
| Providers (TMDB, Cinemeta, add-ons) | Ktor `MockEngine` + recorded JSON fixtures; a shared **contract test** every `CatalogProvider` / `MetadataProvider` must pass | Parsing, errors, rate limit, pagination |
| Telegram layer | `FakeTelegramClient` (scripted chats, pages, file windows, FLOOD_WAIT) | Indexer, resolver, auth state machine |
| DataSource | Media3 `DataSourceContractTest` + fake `TelegramFiles` that simulates slow/partial/out-of-order downloads | Seek re-aim, timeouts, EOF, split joins |
| Database | Room in-memory DB (Robolectric), `MigrationTestHelper` on every schema version | DAOs, FTS queries, migrations |
| Workers | `work-testing` `TestListenableWorkerBuilder` | Incremental sync, retries, constraints |
| Playback integration | Instrumented tests on emulator with local sample files (H.264/HEVC/MKV/MP4/AC3) | Track selection, resume, subtitles |
| Device matrix (manual, per P8/P12) | Low-end TV stick (1 GB), Fire TV, mid phone, Pixel | HDR/DV, 5.1 audio, 4 GB files, slow network |
| Performance | Macrobenchmark + Baseline Profiles (Stage 2), indexing benchmarks | Startup, scroll, index throughput |
| Static | Android Lint, detekt, ktlint, dependency-guard, license report (`licensee`) | In CI on every PR |

Rules: every bug gets a regression test at the lowest layer that reproduces it. CI never touches
real Telegram or TMDB. Live smoke tests run manually with developer credentials.

---

## 12. Risks & limitations

| Risk | Impact | Mitigation |
|---|---|---|
| Telegram FLOOD_WAIT / account restriction from aggressive indexing | High | Throttled indexer, exponential backoff, honor `retry_after`, incremental by message id, user opt-in per chat |
| TDLib file IDs are local to the session | Broken links after re-login | Persist `remote_unique_id` + `(chat, message)`, resolve at play |
| TDLib native size (+20–30 MB per ABI) and 16 KB page alignment | APK size, Play rejection | ABI splits / AAB, verify alignment in CI |
| TDLib wrapper is a third-party project (abandonment) | Medium | Isolated in `:telegram:tdlib`; fallback: official JNI build |
| Seeking MKV files without Cues / with the MP4 moov at the end | Slow start/seek | Warm-up tail fetch, cue-less seek fallback, index probing |
| Device codec gaps (AC3/DTS/HEVC/DV on cheap boxes) | Silent audio / no video | FFmpeg audio fallback, decode-capability probe, version selector |
| Filename chaos in Indian Telegram channels (mixed scripts, emoji, spam tags) | Bad matches | Corpus-driven parser, caption fallback, manual match + lock, confidence thresholds |
| Regional titles missing or mistranslated in TMDB | Unmatched items | Multi-provider fallback, alias table, original-language search, manual match |
| TMDB/Cinemeta rate limits, terms, availability | Degraded metadata | Cache + TTL, rate limiter, user-supplied keys, provider failover |
| Catalog providers that also expose streams (e.g. Stremio add-ons) | Legal / scope creep | The interface has no stream field; add-ons limited to `catalog`/`meta` resources |
| Copyrighted content in users' chats | Store / legal exposure | Neutral tool, no channel directory or discovery, distribute via GitHub/F-Droid first |
| Background limits (Doze, OEM task killers) | Stale index | WorkManager with constraints, foreground service only while the user watches indexing progress |
| TDLib cache filling storage | User frustration | Cache size limit setting, `optimizeStorage`, cleanup after watching |
| `api_hash` extractable from the APK | Unavoidable | Normal for Telegram clients; forks use their own credentials |
| Scope (≈25 modules, 13 phases) | Schedule | Vertical slice early (P2), strict phase exit criteria |

Known limitations by design: no streaming from catalogs, no DRM content, no Telegram chat/messaging
features (media only), Android only in v1.

---

## 13. Decisions needed before Phase 0

1. Project license: GPL-3.0-or-later (recommended) vs Apache-2.0 clean-room.
2. minSdk 26 (recommended) vs 24 (includes legacy Fire TV).
3. Application ID / package root (proposal: `dev.yourstream`).
4. TDLib route: `tdl-coroutines` prebuilt (faster start) vs our own TDLib JNI build (full control).
5. Is Android TV a v1 target (affects P8 test matrix and Stage 2 scope)?

---

## 14. Phase 1 as built: Telegram layer

```
 :app (debug UI, BuildConfig credentials → TelegramConfig, AppLogger)
   │ uses only :telegram:api interfaces
   ▼
 :telegram:api   TelegramManager · TelegramAuthManager · TelegramChatRepository
                 TelegramUserRepository · TelegramFileRepository · models
   ▲ implemented by
 :telegram:core  DefaultTelegramManager (lifecycle) · DefaultTelegramAuthManager (login)
                 DefaultTelegramChatRepository (+ ChatListStore) · DefaultTelegramUserRepository
                 DefaultTelegramFileRepository · TelegramErrorMapper · LoginInput
                 └── talks to the TelegramClient port (+ TelegramEvent stream)
   ▲ port implemented by
 :telegram:tdlib TdlibTelegramClient (tdl-coroutines / TDLib 1.8.69) · TdMappers
                 AndroidNetworkMonitor · TelegramInitializer · Hilt TelegramModule
```

Rules that hold:
- `dev.g000sha256.tdl.*` appears only in `:telegram:tdlib`.
- `:telegram:core` and `:telegram:api` are plain JVM modules. `:telegram:core` has 50 unit tests
  that run against `FakeTelegramClient`.
- Every call returns `AppResult`. TDLib errors go through `TelegramErrorMapper` (FLOOD_WAIT →
  `RateLimited`, `PHONE_CODE_INVALID` → `InvalidInput`, revoked session → `Unauthorized`, …).
- Every TDLib request has a timeout, because TDLib waits forever while offline.

Credentials and the manual test procedure are in [TELEGRAM_SETUP.md](TELEGRAM_SETUP.md).
