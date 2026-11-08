# Functional audit (end of Stage 1)

Scope: the complete flow, from Telegram login through chat selection, indexing, filename
parsing, metadata matching, library creation, search, playback, seeking, buffering, subtitles,
audio selection, watch progress, next episode, catalog integration, offline behaviour and error
recovery. No UI was built.

**How it was tested**

* **Unit and integration tests in every module.** 387 tests, all passing.
* **A new `:e2e` module.** It wires the real modules exactly as the app does (no Hilt). Only two
  things are replaced:
  * Telegram: fake chats plus `SimulatedTelegramFiles`, which carries the real bytes of real
    media files with simulated bandwidth, latency and outages;
  * metadata and catalog providers: fakes.
* **What `:e2e` covers:**
  * `PipelineTest`: the whole chain on a small realistic library;
  * `ScaleAuditTest`: 5,800 messages, and a query-plan audit of every SQL statement Room executes;
  * a lifecycle leak test.
* **Where it ran:** the JVM, with Robolectric and native SQLite. **Nothing in this audit ran on a
  device or against the real TDLib/Telegram servers**; see *Remaining bugs*.

Test run: `./gradlew test` → 387 tests, 0 failures; `assembleDebug` and `lintDebug` pass
(0 errors, 8 warnings). Reports are written to `*/build/reports/` (`scale-audit.md`,
`query-plan-audit.md`, `e2e-metrics.txt`, `search-benchmark.md`, `streaming-benchmark.md`,
`playback-metrics.txt`).

---

## 1. Functionality checklist

**PASS** = works and is covered by tests. **PARTIAL** = works in tests, but something
important is unverified or limited. **FAIL** = broken. **NOT IMPLEMENTED** = absent.

| # | Feature | Status | Evidence / notes |
|---|---|---|---|
| 1 | Telegram login: phone, code, 2FA password, e-mail, QR | **PARTIAL** | State machine and error mapping tested against `FakeTelegramClient` (`DefaultTelegramAuthManagerTest`, `LoginInputTest`, `TelegramErrorMapperTest`, 50 telegram-core tests). The native TDLib client cannot run on the JVM, so a real login was never exercised |
| 2 | Session restore, logout, TDLib restart | PARTIAL | `DefaultTelegramManagerTest` (fake client). Not exercised with real TDLib |
| 3 | Chat list and chat selection | PASS | `DefaultTelegramChatRepositoryTest`, `ChatListStoreTest`. E2E: a disabled chat is never read |
| 4 | Indexing: incremental, full rescan, verify, time budgets, flood waits | PASS | `IndexingEngineTest`. E2E: 5,800 messages in 65 page requests; unchanged re-index = 9 requests and 0 rows queued |
| 5 | Live index updates (new / deleted messages) | PASS | `SearchAndLiveTest` (unit). Not in the e2e chain |
| 6 | Background indexing (WorkManager, survives process death) | PARTIAL | Engine fully tested. Worker classes, constraints and rescheduling are not exercised by tests. `RUNNING` chats are reset at start |
| 7 | Filename parsing: titles, years, S/E, resolution, quality, languages | PASS | `CorpusTest`, `ConfidenceAndRobustnessTest`. **Bug fixed:** dot-separated language lists (`Tam.Tel.Hin`) were not recognised |
| 8 | Duplicate detection (exact / probable) | PASS | `IndexingEngineTest`, `ParsedMetadataTest` |
| 9 | Metadata matching (TMDB, Cinemeta), confidence, review queue, retries | PASS | `MatchingTest`, `LibraryMetadataServiceTest`, provider tests with recorded and changed JSON. E2E: genres, IMDb id and episode titles applied. Live TMDB not called (no key in CI) |
| 10 | Library creation: movies, series → seasons → episodes, versions, subtitles attached | PASS | `LibrarySyncTest`, `MergeAndManualTest`, `LibraryScaleTest`. E2E: 2 versions of one movie, 3-episode series, the Telegram `.srt` linked to S01E01 |
| 11 | Manual correction: merge, rename, link, exclude | PASS | `MergeAndManualTest` |
| 12 | Search: exact, prefix, typo, transliteration, filters, sort, paging | PASS | `LibrarySearchEngineTest`, `SearchBenchmarkTest` (50k items). E2E queries |
| 13 | Natural-language assistant | PASS | `IntentParserTest`, `LocalMediaAssistantTest`. E2E "show Tamil movies", "continue watching ben 10" |
| 14 | Playback, small files (360p, 6 s) | PASS | `TelegramPlaybackTest`, e2e |
| 15 | Playback, large / long files (85 min, 148 MB) | PASS | Far seek downloads 0.6–0.8 % of the file. E2E on a 1 MB/s, 150 ms network |
| 16 | 720p (index at the end of the file) and 1080p | PASS | Resolution tests; e2e chooses the 1080p version and plays 720p on a slow network |
| 17 | Seeking (single, repeated, scrub, far) | PASS | Median repeated seek 291 ms; far seek 139 ms (fast network) / 575–864 ms (poor network) |
| 18 | Buffering / adaptive streaming | PASS | `StreamingBenchmark`: adaptive halves rebuffers on fluctuating mobile |
| 19 | Embedded subtitles | PASS | `TrackSelectionPlaybackTest` |
| 20 | External subtitles from Telegram (download, re-encode, detect language) | PASS | E2E: Tamil `.srt` named as English is detected as Tamil and selected. **Bug fixed:** loading could hold playback start for 15 s per file |
| 21 | Audio track selection: preferences, wrong tags, fallback, remembered | PASS | `TrackSelectionPlaybackTest`. E2E: Hindi default → Tamil preferred |
| 22 | Watch progress: position, %, completion, count, sessions | PASS | `WatchHistoryTest`, `WatchIntelligenceTest`. E2E: a real playback to the end marks the movie COMPLETED |
| 23 | Next episode (S01E05 100 %, S01E06 35 % → S01E06) | PASS | `NextEpisodePolicyTest`. E2E: finishing E01 → E02; 35 % of E02 → resume E02 |
| 24 | Continue Watching / Recently Watched / Recently Added / Favorites / Abandoned | PASS | `WatchIntelligenceTest`, e2e, scale audit |
| 25 | Catalog integration (discover, library presence, metadata fallback, cache) | PASS | `CatalogManagerTest`, `CatalogSyncTest`, e2e (Vikram present, Jailer not). Live Stremio add-ons not called |
| 26 | Multiple languages (file names, audio, subtitles, catalogs) | PASS | Parser, tracks and e2e |
| 27 | Poor network | PASS | Simulated 1 MB/s at 150 ms latency; benchmark "poor mobile" |
| 28 | Network switching | PASS | Downloads re-issued after a switch (e2e). **Bug fixed:** TDLib could get network types out of order |
| 29 | Telegram unavailable | PASS | E2E: index runs fail cleanly; library, search, assistant and watch lists work; downloaded files play |
| 30 | Metadata provider unavailable / changes format | PASS | E2E and provider tests: no crash, titles kept, fallback to cache |
| 31 | Offline playback of a file not yet downloaded | PARTIAL | No crash. Playback waits for the network (by design) and now reports "waiting for network"; it has no deadline |
| 32 | A media file disappears | PASS | E2E: deleted 1080p → 720p version; verify marks it deleted; both gone → FILE_UNAVAILABLE error |
| 33 | Database migration safety | PASS | `MigrationTest` (v1 → v9), `DatabaseSafetyTest` (backup, salvage, corrupted file) |
| 34 | Process death recovery | PARTIAL | Session store, index state reset, WorkManager persistence, progress every 10 s, all unit-tested. A real process kill was not exercised |
| 35 | Crash handling, safe mode, diagnostics | PASS | `DiagnosticsTest`. The handler is installed before Hilt |
| 36 | Thousands of media records | PASS | 5,800 (e2e pipeline), 10,000 (library), 50,000 (search) |
| 37 | Memory: no leaks across player screens | PASS | E2E: 15 open/seek/release cycles → 0 sessions, 0 downloads, threads 10 → 14 (pool warm-up, not per player) |
| 38 | Battery-conscious background work | PARTIAL | Constraints and idle sampling in place, and needless re-sync removed. **No on-device battery measurement** |
| 39 | Download a title for offline viewing | **NOT IMPLEMENTED** | Only the streaming cache exists; there is no explicit "save offline" |
| 40 | Android TV / D-pad, real codecs on devices | **NOT IMPLEMENTED** (Stage 2) | Robolectric uses shadow codecs |

No feature is **FAIL** after the fixes below.

---

## 2. Bugs found and fixed in this audit

| Severity | Bug | Fix |
|---|---|---|
| High | **Dot-separated language lists ignored by the parser.** `Leo.2023.Tam.Tel.Hin.480p.mkv` got no languages, the most common Telegram naming. Languages drive search filters, the assistant, audio and subtitle choice, and catalog grouping | Runs of ≥ 2 distinct abbreviations, never at the start of the name (`Detectors.SPACED_SHORT`); corpus cases added |
| High | **External subtitles blocked playback start.** Files loaded one after another with a 15 s timeout each, so a slow Telegram delayed the video by 15 s × files | Parallel loading, 5 s bound (`ExternalSubtitleLoader.load`); e2e asserts < 5 s (measured 11 ms) |
| High (perf/battery) | **Unchanged rows re-queued the whole library.** Library and search triggers fired on *any* update of `media` / `media_parsed`, including identical rewrites, timestamp touches and download-state changes. A verify run re-synced and re-indexed every item | `WHEN OLD.x IS NOT NEW.x …` over the columns that matter, built from the live schema; volatile columns excluded. Library sync after an unchanged re-index: 252 ms → 0.6 ms; rows queued after verify: 0 (asserted) |
| Medium (race) | **`AndroidNetworkMonitor` race.** One coroutine per connectivity callback, so a quick Wi-Fi → mobile switch could reach TDLib out of order, and every signal-strength change sent a `setNetworkType` | De-duplicating `StateFlow` with a single ordered collector |
| Medium (battery) | **Playback sampler ticked every 500 ms forever**, while paused or with the player in the background | 5 s idle tick; back to fast on play |
| Medium (memory) | **One OkHttp engine per provider** (~8 connection pools and dispatcher thread sets) | One shared `OkHttpClient` |
| Medium (startup/battery) | **Diagnostics checks ran at every app start**, including a full-database `quick_check` | Run when the Diagnostics tab is first shown |
| Medium (DB perf) | **Default library list sorted the whole table per page** (`USE TEMP B-TREE`) | Index `(sort_title, id)`, DB **v9** (additive auto-migration, tested): page 2.0 → 0.3 ms at 4k items |
| Low (DB perf) | **"Recently added" sorted on an expression** | Uses the indexed column: 4.0 → 0.9 ms |
| Low | **`controller.open()` returned the media before track preparation** (no sideloaded subtitles or context) | Returns what actually plays |
| Low (memory) | **Seek-latency history grew without bound** | Last 200 |
| Low (perf) | **Event journal O(n) `size()` per event; file log opened/closed the file per line** | Counter; batched writes |
| Low (UX) | **Offline, not-yet-downloaded file: endless PREPARING with no reason** | State reports "waiting for network" |
| Test infra | **`SimulatedTelegramFiles.observeFile` race.** It read state before subscribing, so a fast download could be missed. It made the subtitle stall look like a flaky test | Subscribe first, as the real repository does |

Temporary hacks: none found in main code; a sweep for TODO, debug prints and stray
suppressions came back clean. Test-only scaffolding added during the audit was removed.

---

## 3. Remaining bugs and limitations

| Priority | Issue | Impact |
|---|---|---|
| **High** | **Not verified on a device.** Real TDLib, real Telegram servers, real codecs, Doze/App Standby, real process kills, real battery drain | All evidence is JVM/Robolectric with simulated Telegram transfers. Device testing is the first job before Stage 2 |
| Medium | **Indexing write cost.** About 1.2 ms per new message on the JVM (~500 messages/s; a phone will be slower). Unchanged rows are still rewritten (≈0.55 ms each), though nothing downstream is queued any more | Initial indexing of very large channels takes minutes on a phone. Skip unchanged writes (compare before update) |
| Medium | **The database opens on first injection.** If a screen injects it before the startup coroutine has opened it, the main thread waits | A large migration could block the first screen briefly |
| Medium | **Offline wait has no deadline.** A not-downloaded file waits for the network indefinitely (bounded only by `maxOfflineWaitMs` in the streamer) | The UI must show "waiting for network" and let the user cancel |
| Low | **Maintenance may never run on some devices.** `MaintenanceWorker` requires charging + idle, and lint warns some devices never report both | Housekeeping only; the integrity check also runs from the Diagnostics screen |
| Low | **Slow search shapes at 50k items.** "Filters only (soft)" 59 ms; "file-name words" 22 ms | Fine for typing, borderline for live-as-you-type filter chips |
| Low | **Prefetch waste.** 29 % in next-episode scenarios and 65 % when seek-heavy (baseline: 37 % / 77 %) | Design trade-off for instant next episode; tunable in `PrefetchConfig` |
| Low | **Worker classes untested.** WorkManager workers (`IndexChatWorker`, `MetadataSyncWorker`, `MaintenanceWorker`) aren't instantiated in tests | Wiring regressions would only show on a device |
| Low | **Controller background scope.** `TelegramPlaybackController` creates its own background scope when none is injected (intended to outlive release for the final progress save) | Harmless; the factory could pass a shared one |
| Cosmetic | **Lint warnings:** no app icon, not the latest target SDK, AGP 9.5.1 available, Compose autoboxing hints in debug screens | — |

---

## 4. Performance measurements

JVM (Robolectric, native SQLite, in-memory). Absolute numbers on a mid-range phone will be
roughly 2–5× higher; the ratios are what matter.

### End-to-end pipeline (`scale-audit.md`, 5,800 messages → 3,879 items)

| Stage | Time |
|---|---|
| Index 5,800 messages (3 chats, 65 page requests) | 8.3 s |
| Library sync (first) | 2.7 s |
| Search index build | 0.4 s |
| Metadata match run (≤ 1,000 items, 1,823 provider calls) | 1.5 s |
| Incremental re-index, nothing new (9 requests) | 0.44 s |
| Verify run (re-reads all messages) | 3.6 s, **0 rows re-queued** |
| Library sync, nothing new | **0.6 ms** (was 252 ms) |
| Search: exact / typo / filters / series | 2.6 / 5.2 / ~6 / ~8 ms |
| Library page, raw SQL (LIMIT 50) | **0.3 ms** (was 2.0 ms) |
| Assistant "unwatched tamil movies" / "what should I watch next?" | 18 / 44 ms |
| Continue watching (300 tracked) / recently watched / recently added | 23 / 7 / 0.9 ms |
| Small library: index → searchable | 0.12–0.9 s |

### Search at scale (`search-benchmark.md`)

| Query (p50) | 20k items | 50k items |
|---|---|---|
| Exact title | 4.0 ms | 1.7 ms |
| Typo | 3.9 ms | 1.4 ms |
| Transliteration | 3.6 ms | 1.4 ms |
| Title + season | 2.0 ms | 2.7 ms |
| File-name words | 34 ms | 22 ms |
| Filters only (soft) | 29 ms | 59 ms |
| Hard filters + sort | 10 ms | 17 ms |
| Cached result pages | 2.9 ms | 0.33 ms |

The index builds at 4.7–8.7k items/s, and the vocabulary takes ~6 MB of heap.

### Playback (`playback-metrics.txt`, e2e)

| Measure | Value |
|---|---|
| Startup 360p / 480p / 720p (index at the end) / 1080p | 129 / 174 / 255 / 196 ms |
| Startup 85 min, 148 MB file: fast / poor network | 354 ms / 1.46 s |
| Seek to 00:05 / to 01:20:00 (fast network) | 54 / 139 ms |
| Far seek, poor network (1 MB/s, 150 ms) | 575–864 ms |
| Downloaded after far seek | 0.6–0.8 % of the file |
| Repeated seeks: median / max | 291 / 563 ms |
| Slow network 720p startup, rebuffers | 2.8 s, 0 |
| Recovery after an outage | 3.3 s |
| External subtitle: open → ready | 11 ms → 77–254 ms (was ≥ 15 s) |
| Multi-audio track decision | 0.3–1.5 s (Robolectric) |
| Peak Java heap during long-file playback | ~180 MB (test JVM, includes Robolectric) |

### Adaptive streaming (`streaming-benchmark.md`, 2 Mbit/s episodes)

| Scenario | Rebuffers/h adaptive vs baseline | Waste |
|---|---|---|
| Mobile 0.5–4 Mbit/s | 13 vs 39 | 8 % vs 6 % |
| Poor mobile 2.2 Mbit/s | 33 vs 79 | 4 % vs 1 % |
| Seek-heavy | 0 vs 0 | 65 % vs 77 % |
| Next episode | startup 120 vs 300 ms | 29 % vs 37 % |

### Memory, threads, leaks

* 15 player screens opened, sought and released → 0 streaming sessions, 0 downloads; threads
  10 → 14 (shared pools warming up); heap after GC 74 MB in the test JVM.
* One shared OkHttp client instead of about 8.

### Query-plan audit (`query-plan-audit.md`)

All ~240 distinct statements Room executed were checked with `EXPLAIN QUERY PLAN`. Remaining
full scans of large tables are intentional:

* the vocabulary load (once, then incremental);
* the genre list;
* the test's own query.

---

## 5. Architecture problems

1. **Two network monitors.** The Telegram `AndroidNetworkMonitor` and the playback
   `AndroidConnectivity` each register their own callback and model network state their own
   way.
2. **Several app-wide coroutine scopes** (app, library, metadata, Telegram, playback), each with
   its own exception handler and no common parent. Startup ordering and shutdown aren't
   expressed anywhere.
3. **Database lifecycle is tied to Hilt injection.** `DatabaseOpener` is safe, but *when* it runs
   depends on who injects first.
4. **Three overlapping full-text indexes.**
   * `media_fts`: file search in the index screens;
   * `library_item_fts`: library text filter;
   * `search_fts`: the search engine.

   `search_fts` supersedes the other two for user-facing search.
5. **Duplicated test fixtures.** `SearchFixture`, `AssistantFixture`, `WatchFixture` and several
   `Fakes.kt` are near-copies. `:e2e` now holds the canonical fake Telegram and providers.
6. **Debug app module.** It depends on every module and hosts ~3k lines of debug screens. It is
   correctly isolated as Stage-1 scaffolding, but it is not an architecture for Stage 2.
7. **Watch lists recompute whole lists on every relevant change.** Bounded (≤ 500 tracked items,
   ~20 ms), but O(n) per change.
8. **Version choice lives in the playback resolver** (`LibraryMediaSourceResolver.rank`), while
   version data lives in the library. The library could own "best playable version".

---

## 6. Recommended fixes (in order)

1. **Device test pass.**
   * Pass 1: real login, chats, indexing of a large channel, playback of 720p/1080p/4K HEVC,
     subtitles, Wi-Fi ↔ mobile, airplane mode, process kill (`adb shell am kill`), Doze, battery
     stats (`dumpsys batterystats`).
   * Pass 2: a low-RAM Android TV stick.
2. **Skip unchanged index writes.** In `RoomIndexStore.upsertOne`, compare the parsed and
   message fields and only `touch` `last_seen_at`. Expected ~2× faster rescans and less flash wear.
3. **Open the database explicitly** in the startup sequence (or behind a `suspend` provider), so
   no main-thread injection can wait on a migration.
4. **One `NetworkState` service** in `core:network` feeding TDLib, playback and WorkManager-aware
   policies.
5. **One application scope tree** (`ApplicationScope` → named child supervisors), with startup
   steps expressed in one place.
6. **Offline-wait deadline:** after N minutes without network, end with a clear error.
7. **Worker tests** with `androidx.work:work-testing`: constraints, retry and continuation chains.
8. **Consolidate FTS:** library text filtering via the search engine; drop `library_item_fts`
   once the debug screens no longer need it.
9. **A shared `testFixtures` module** for fake Telegram, providers and library seeding.
10. **Explicit "download for offline"** (Stage 2 feature), built on the existing streamer and
    `StreamCache`.

---

## 7. API / interface documentation

See [API.md](API.md) for the final module interfaces.
