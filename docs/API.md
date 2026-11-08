# API / interface reference (end of Stage 1)

These are the interfaces Stage 2 builds on. The UI talks only to the interfaces in this list,
never to Room DAOs or TDLib directly. Everything is Kotlin and coroutine-based:

* `suspend` for one-shot calls;
* `Flow` for live data;
* `PagingData` for long lists.

Expected failures come back as values (`AppResult`/`AppError`), not exceptions. Implementations
are bound with Hilt (`@Binds` in each module's `*Module`).

Module dependency direction:

```
app (debug UI) → watch / assistant / search / playback / catalog / metadata / library / media:indexer → telegram:api, core:*
```

---

## core:common

| Type | Purpose |
|---|---|
| `sealed interface AppResult<T> { Ok(value), Err(error) }` | Every fallible operation |
| `sealed interface AppError` | `Network`, `Timeout`, `RateLimited(retryAfterSeconds)`, `InvalidInput`, `Unauthorized`, `InvalidState`, `NotConfigured`, `NotFound`, `Telegram(code, rawMessage)`, `Remote(status)`, `Unknown(cause)`. `message` is safe to show (no secrets) |
| `fun AppError.isTransient()` | A retry can help (network, timeout, 429, 5xx) |
| `RetryPolicy(maxAttempts, baseDelayMs, maxDelayMs, factor, jitter)`, `suspend fun retrying(policy, retryIf, onRetry) { attempt -> AppResult }` | Exponential backoff with jitter; honours Retry-After; never swallows cancellation |
| `suspend inline fun guarded(operation) { AppResult }` | Unexpected exceptions → `AppError.Unknown` (provider/format changes never crash) |
| `interface Logger { d/i/w/e }`, `redactPhone()` | Logging port; callers never pass secrets |
| `fun interface Clock { nowMillis() }` | Injectable time |
| `fun interface MemoryTrimmable`, `object MemoryPressure { register(t); trim(critical) }` | Caches release memory under pressure |

## core:database

| Type | Purpose |
|---|---|
| `YourStreamDatabase` (Room, **v9**) | All persistent state; auto-migrations v1 → v9; triggers recreated on every open |
| `DatabaseOpener.open(context, name, log): Pair<YourStreamDatabase, DatabaseOpenReport>` | Back up before an upgrade → open eagerly → quarantine and salvage on failure. Never destructive |
| `DatabaseOpenReport(outcome: CREATED/OPENED/MIGRATED/SALVAGED/RECREATED, previousVersion, backup, quarantined, salvaged, error)` | How the database came up |
| `DatabaseIntegrity.check(db, file): IntegrityReport`, `repair(db, file): RepairReport` | `quick_check`, foreign keys, FTS, JSON validity; repair without losing user data |
| `OpenedDatabase(db, report, file)` | Hilt-provided |

DAOs are internal to the data modules; the UI must not use them.

## core:diagnostics

| Type | Purpose |
|---|---|
| `Redactor.redact(text)` | Strips phone numbers, keys, tokens, codes, hex secrets |
| `RotatingFileLog(dir, name, maxBytes, maxFiles)` | `append`, `appendNow`, `tail(n)`, `readAll()`, `clear()`; never throws |
| `EventJournal(clock, file)` | `record/info/warn/error(category, message, throwable)`, `recent(limit, minSeverity, category)`, `problemCounts()` |
| `CrashStore(dir, clock)`, `StartupGuard(dir, clock)` | Crash reports; crash-loop detection → `Startup.safeMode`; `markStable()`, `forceSafeMode(on)` |
| `interface HealthCheck { name; category; suspend check(): CheckOutcome }`, `DiagnosticsRunner(checks, journal, timeoutMs).run()` | Self-tests; checks that throw or hang are reported, not propagated |
| `DiagnosticReport.format(info, checks, events, crashes, logTail)` | Redacted text report |
| `PlaybackSessionStore(file)` | `save(PlaybackSession)`, `interrupted(now)`, `clear()`: resume after process death |

## telegram:api

| Interface | Members |
|---|---|
| `TelegramManager` | `status: StateFlow<TelegramStatus>` (Stopped/NotConfigured/Starting/Running/Restarting/Failed), `connectionState: StateFlow<TelegramConnectionState>`, `start()`, `restart()`, `stop()`, `reconnect()`, `onNetworkChanged(NetworkType)` |
| `TelegramAuthManager` | `state: StateFlow<AuthState>`, `submitPhoneNumber`, `submitCode`, `resendCode`, `submitPassword`, `submitEmailAddress`, `submitEmailCode`, `requestQrCodeLogin`, `logOut` |
| `TelegramChatRepository` | `chatList(list[, filter]): Flow<ChatListState>`, `loadMore`, `loadAtLeast`, `observeChat`, `getChat`, `getChatDetails` |
| `TelegramUserRepository` | `currentUser: StateFlow<TelegramUser?>`, `refreshCurrentUser`, `getUser` |
| `TelegramMessageRepository` | `events: Flow<TelegramMessageEvent>`, `searchMedia(chatId, filter, fromMessageId, limit ≤ 100)`, `lookupMessages(chatId, ids)` |
| `TelegramFileRepository` | `fileUpdates`, `getFile`, `observeFile` (current state first, then changes), `download(fileId, priority, offset, limit)`, `cancelDownload`, `downloadedPrefixSize`, `readPart`, `deleteLocalCopy` |

## media:api / media:indexer

| Interface | Members |
|---|---|
| `MediaIndexer` | `enableChat(chatId)`, `disableChat(chatId, purge)`, `schedule(chatId, mode = INCREMENTAL / FULL_RESCAN / VERIFY)`, `scheduleAll()`, `cancel(chatId)`. Work runs in WorkManager: one unique chain per chat, survives process death |
| `MediaIndexRepository` | `search(MediaQuery): Flow<PagingData<MediaSummary>>`, `count`, `getMedia`, `duplicatesOf`, `observeChats(): Flow<List<ChatIndexStatus>>`, `observeChat` |
| `fun interface MediaIndexListener { onIndexChanged() }` | The library and metadata sync subscribe; calls are coalesced |

## library

| Interface | Members |
|---|---|
| `MediaRepository` | `search(LibraryQuery)`, `count`, `recentlyAdded(kinds)`, `favorites()`, `getItem(id): LibraryItemDetails?`, `versions(target)`, `setFavorite`, `updateItem(id, title, year)`, `deleteItem(id, keepUserData)`, `restoreItem`, `mergeItems(target, source)`, `linkMedia(mediaId, target)`, `excludeMedia`, `sync()` |
| `MovieRepository` / `SeriesRepository` | `movies(query)`, `movie(id)` / `series(query)`, `seriesDetails(id)`, `seasons(seriesId): Flow<List<Season>>` |
| `EpisodeRepository` | `episodes(seriesId, seasonId?)`, `episode(id)`, `nextEpisode(episodeId)` (next with a file), `versions(episodeId)` |
| `WatchHistoryRepository` | `updateProgress(target, mediaId, positionMs, durationMs): WatchProgress` (decides completion: ≥ 90 % or the last 3 min of > 20 min), `markWatched`, `markSeasonWatched`, `clearProgress`, `progress(target)`, `continueWatching`, `recentlyWatched`, `history` |
| `SubtitleRepository` / `AudioTrackRepository` | Subtitle and audio tracks per title/file and the per-title preference |
| `PlaybackTarget(itemId, episodeId?)` | What to play: a movie/video, or one episode |

`LibraryQuery`: text, kinds, watch filter, years, language, minimum resolution, favorites, sort
(TITLE, RECENTLY_ADDED, RECENTLY_WATCHED, YEAR, SIZE), reversed, include hidden/unavailable.

## metadata

| Type | Members |
|---|---|
| `MetadataProvider` (TMDB, Cinemeta) | `search(MetadataQuery)`, `movie`, `series`, `season`, `episode`, `findByImdbId`, `capabilities`, `isConfigured` |
| `MetadataProviderManager` | Fallback across providers, cache (fresh/stale), per-provider circuit breaker: `search`, `movie/series/season/episode(ref)`, `status(): List<ProviderStatus>` |
| `LibraryMetadataService` / `MatchLibraryMetadata` | `processPending()` (auto-accepts only high confidence), `rematch(itemId)`, `searchCandidates(itemId, title?, year?)`, `confirm(itemId, ref)`, `reject(itemId)`, `refreshStale()` |

## catalog

| Type | Members |
|---|---|
| `CatalogProvider` (Stremio add-ons, Indian catalogs) | `describe()`, `page(catalog, skip, genre)`, `search(query, type)`, `metadata(ref)` |
| `CatalogManager` | `refreshAvailability()`, `availability()`, `catalogs(type)`, `languages()`, `genres(type)`, `page(catalog, skip)`, `discover(DiscoverRequest(language, type, genre, cursor))`, `search(query, type)`, `metadata(item)` (provider → external metadata → library → listing), `withLibraryPresence(items)`: which titles you own on Telegram |

Catalogs are information only; what plays is always the user's own Telegram media.

## search

| Type | Members |
|---|---|
| `LibrarySearch` | `search(SearchRequest(text, filters, sort, reversed, offset, limit)): SearchPage(hits, total, nextOffset, interpretation, tookMicros)`, `suggest(prefix, limit)` |
| `SearchFilters` | kinds, languages, genres, years, season, min resolution, qualities, chats, providers, watch state, include hidden/unavailable |
| Query syntax | Free text with soft filters ("tamil comedy 2023 1080p s2") and operators (`lang:`, `genre:`, `year:2010-2020`, `season:`, `res:`, `quality:`, `type:`, `in:<chat>`, `provider:`) |

## assistant

| Type | Members |
|---|---|
| `MediaAssistant` | `ask(text): AssistantResponse(interpretation, plan, message, items, total, tookMs)` |
| `IntentParser.parse(text): Interpretation(intent, entities, confidence, notes)` | Deterministic; intents SEARCH / EPISODES / CONTINUE_WATCHING / RECOMMEND / PLAY |
| `Planner.plan(interpretation): QueryPlan` | Pure |
| `fun interface IntentModel` | Optional on-device model (none shipped); validated and merged, never required |

## watch

| Type | Members |
|---|---|
| `WatchIntelligence` | `continueWatching(limit): Flow<List<ContinueEntry>>`, `recentlyWatched`, `recentlyAdded(limit, kinds)`, `favorites()`, `abandoned()`, `nextEpisode(seriesId): NextEpisodeDecision`, `summary(itemId): WatchSummary`, `episodes(seriesId)`, `dismiss(itemId)`, `restore(itemId)` |
| `NextEpisodePolicy.decide(episodes)` | Pure. RESUME/START/NONE with a reason (IN_PROGRESS, NEXT_AFTER_COMPLETED, NEW_EPISODE, NOT_STARTED, REWATCH, EARLIER_GAP, SERIES_FINISHED) and skipped missing episodes |
| `WatchPolicies`, `WatchPolicy` | Status, percentage, abandonment thresholds |

## playback

| Type | Members |
|---|---|
| `TelegramPlaybackControllerFactory.create(config)` | One controller per player screen; shared streamer, governor, monitor and cache |
| `TelegramPlaybackController` | `player` (for a `PlayerView`), `state: StateFlow<PlaybackState>`, `metrics`, `tracks: StateFlow<TrackState>`, `recoveryLog` |
| ↳ commands | `open(target, mediaId?, startPositionMs?, playWhenReady)`: returns the media that plays, `play(media)`, `pause/resume/togglePlayPause`, `seekTo`, `seekBy`, `beginScrub/endScrub`, `selectAudio(id)`, `selectSubtitle(id?)`, `retry()`, `stop()`, `release()` |
| `PlaybackState` | status (IDLE/PREPARING/BUFFERING/READY/ENDED/ERROR), position, duration, buffered, media, `error: PlaybackError(kind NETWORK/FILE_UNAVAILABLE/UNSUPPORTED_FORMAT/DECODER/UNKNOWN, retryable)`, video size, seeking, `recovery` ("retry 2 in 4 s", "switching to another version", "waiting for network") |
| `PlaybackRecoveryPolicy` | Backoff retries online, waits offline, switches version on file/decoder errors |
| `MediaSourceResolver` | `resolve(target, mediaId?)` (best playable version; deleted files fall through), `resolveExcluding(target, ids)` |
| `TrackPreferenceStore` | `preferences()`, `setPreferences(LanguagePreferences(audio, subtitles, fallbackLanguage, subtitleMode))`, remembered per-movie / per-series choices |
| `PlaybackSettings` | Adaptive on/off, transfer limits, buffer config, prefetch on mobile data |

## Threading and lifecycle contract

* **Threads:** all repository and engine calls are main-safe (Room and TDLib calls suspend off
  the main thread). `TelegramPlaybackController` must be used on the main thread.
* **Controller lifecycle:** release the controller when its screen goes away
  (`ViewModel.onCleared`). It saves progress and cancels its downloads.
* **Flows:** flows from repositories are cold or state flows bound to the caller's scope; collect
  them with lifecycle awareness (`collectAsStateWithLifecycle`).
* **Background work:** goes through WorkManager (`IndexScheduler`, `MetadataSyncWorker`,
  `MaintenanceWorker`) and respects battery, storage and network constraints.
