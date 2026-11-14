# Product audit (Phase 0)

Audit of the repository at commit `5b09160` (release v0.10.0), 11 November 2026. Read-only:
no source code was changed for this audit. Every statement below was checked against the code
(file paths given); where the documentation says something different, the code wins and the
difference is listed under [Documentation drift](#9-documentation-drift).

Evidence used: source reading, `./gradlew testDebugUnitTest test` (all JVM tests pass, run
11 Nov 2026), `./gradlew :app:assembleDebug` (builds), `:app:lintDebug` (result in §8), the
release workflow run for v0.10.0. **Nothing in this audit was verified on a phone or against
real Telegram servers**; the test suite runs on the JVM/Robolectric with a fake Telegram client.

---

## 1. Architecture

### 1.1 Shape

40 Gradle modules, about 38,000 lines of main Kotlin and 13,500 lines of tests (80 JVM test
classes, 0 instrumented tests).

| Layer | Modules | Main lines | Role |
|---|---|---|---|
| App | `app` | 1.1k | Hilt wiring, `MainActivity`, workers bootstrap, in-app updater, logger, health checks |
| UI | `ui:core`, `ui:screens` | 10.1k | Compose design system, all screens, navigation, ViewModels, settings |
| Telegram | `telegram:api`, `telegram:core`, `telegram:tdlib` | 2.8k | Interfaces, auth/chat/file/message repositories, TDLib client (tdl-coroutines) |
| Media index | `media:api`, `media:indexer`, `media:filename`, `media:parser` | 3.9k | Chat scanning (WorkManager), filename/caption parsing, live updates |
| Library | `library` | 1.5k | Movies / series / seasons / episodes / versions, paging queries, sync from index |
| Metadata | `metadata:*` (api, core, tmdb, cinemeta, sync) | 2.0k | Provider manager, matching, TMDB + Cinemeta, background matching worker |
| Playback | `playback:*` (stream, media3, adaptive, tracks, torrent, testing) | 5.3k | Telegram byte streaming, Media3 controller, adaptive buffering, tracks, torrents |
| Catalogs / add-ons | `catalog:*` | 1.8k | Stremio/Nuvio add-on protocol, catalog rows, built-in Indian catalog |
| Search / assistant / watch | `search:*`, `assistant:*`, `watch:*` | 2.6k | Offline search, natural-language queries, continue watching / next episode |
| Data | `core:database` | 3.1k | Room (v12, 12 exported schemas, migration tests) |
| Support | `core:common`, `core:network`, `core:language`, `core:diagnostics`, `sync` | 1.7k | Results/errors, HTTP + secure DNS, languages, logs/crash files, encrypted progress sync |

### 1.2 Data flow

```
TDLib ──► telegram:core (auth, chats, messages, files)
            │
            ├─► media:indexer ──(WorkManager, 8-min runs)──► Room: media, media_parsed, scan_cursor
            │                                                   │
            │                         library:LibrarySynchronizer ▼
            │                                        Room: library_item, season, episode, version
            │                                                   │
            │                     metadata:sync (TMDB/Cinemeta) ▼  posters, ids, cast
            │
            └─► playback:stream (TDLib downloadFile ranges) ─► playback:media3 (ExoPlayer DataSource)
UI (Compose) ◄── ViewModels ◄── library / search / watch / catalog flows
```

### 1.3 What is sound (keep)

* **Clear module boundaries with `*:api` interfaces** (`telegram:api`, `media:api`,
  `metadata:api`, `catalog:api`): implementations are swappable and testable.
* **`AppResult` / `AppError` everywhere** (`core/common`): failures are values, not exceptions;
  `HttpClients.getJson` maps every HTTP failure to a typed error.
* **Indexing is incremental and resumable by design**: per-chat, per-filter `scan_cursor`
  rows; head/backfill/verify phases; unique `(chat_id, message_id)`; runs end inside
  WorkManager's 10-minute window and queue a continuation (`IndexChatWorker`, `IndexingEngine`).
* **Playback resolves the file at play time** (`LibraryMediaSourceResolver`): the message is
  looked up again (file ids are per session), deleted files fall through to the next version.
  `PlaybackRecoveryPolicy` is a pure, tested error → action table.
* **Database care**: exported schemas, auto-migrations 1→12, `MigrationTest`, `DatabaseSafety`
  (integrity check, safe opening).
* **Backups disabled** for the TDLib session (`allowBackup=false`, `data_extraction_rules.xml`),
  session in `noBackupFilesDir`.
* **Progress sync key wrapped by Android Keystore** (`sync/ProgressSync.kt`, AES-GCM).
* **Stored logs are redacted** (`AppLogger` → `Redactor`), phone numbers are redacted before
  logging (`redactPhone`).

### 1.4 Architectural weaknesses

1. **Telegram is hard-wired into the data model.** `media` is keyed by `(chat_id, message_id)`
   and holds TDLib file ids (`core/database/.../Entities.kt`); the library, resolver and UI
   assume a Telegram message behind every file. There is no source abstraction, so local files
   (Phase 8) need a schema migration (a `source` column and a generic locator) first.
2. **Several app-wide coroutine scopes with no common parent** (app, library, metadata,
   Telegram, playback); startup order and shutdown are implicit (noted in `docs/AUDIT.md` §5,
   still true).
3. **Two network monitors** (`telegram/tdlib/AndroidNetworkMonitor`,
   `playback/media3/AndroidNetworkSignals`) model connectivity separately.
4. **Three overlapping full-text indexes** (`media_fts`, `library_item_fts`, `search_fts`): extra
   writes per indexed message.
5. **`ui:screens` is one 8k-line module** holding every screen and ViewModel. Workable, but
   build times and ownership suffer.
6. **Scope creep away from the core product**: Stremio/Nuvio add-ons, a torrent engine
   (libtorrent, about 30 MB of native code per ABI), a natural-language assistant, built-in
   third-party catalogs. They are useful to some users but are not "Telegram-first", raise
   legal and privacy questions, and dominate APK size.

---

## 2. Feature status matrix

Status: **Working** = implemented end to end and covered by JVM tests (still not device-verified
unless stated) · **Partial** · **Simulated** = only exercised against fakes · **Docs only** =
documented, not implemented · **Missing**.

| Area | Feature | Status | Evidence / gap |
|---|---|---|---|
| Telegram auth | Phone → code → 2FA password, email code, QR login, resend | Working | `DefaultTelegramAuthManager`, `AuthState` covers all TDLib states; `Account.kt` UI |
| | Built-in API id/hash, never asked from users | Working | `app/build.gradle.kts` `secret()`, CI secrets, `forgetLegacyCredentials` |
| | Session persistence | Working | TDLib db in `noBackupFilesDir/tdlib/db` |
| | Logout | Partial | `logOut()` ends the TDLib session; **local library, watch history, caches, TMDB key, sync settings stay** |
| | Account switching | Missing | No per-account data separation; a second account sees the first account's library |
| | Expired / revoked session | Partial | TDLib moves to `WaitPhoneNumber`; no message explaining why, library stays with dead file ids |
| | TDLib database encryption | Missing | `databaseEncryptionKey` empty (`TelegramConfig`); sandbox only |
| Indexing | Incremental head + backfill, cursors, continuation | Working | `IndexingEngine`, `scan_cursor` |
| | Resume after process death | Working (simulated) | WorkManager re-runs; cursors persisted per page; tests with fake Telegram |
| | Duplicate detection | Working | unique `(chat_id, message_id)`; versions grouped by parsed title |
| | Deleted-message detection | Working | VERIFY mode + live `updateDeleteMessages` (`LiveIndexUpdater`) |
| | Rate limits / retries | Working | FLOOD_WAIT handling, `Result.retry` with attempt cap |
| | User pause / resume | Missing | Only cancel exists (`IndexScheduler.cancel`); `PAUSED` means "time budget ended" |
| | Cancel from the UI | Missing | `MediaIndex.cancel` / `IndexScheduler.cancel` exist; no screen calls them |
| | Wi-Fi-only / battery-aware indexing | Partial | Network CONNECTED only; battery-not-low only for reparse and periodic sync |
| | Explicit states (CONNECTING, SCANNING, PROCESSING, RECOVERING) | Partial | `IndexState` = IDLE, QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED |
| | Progress reporting | Working | `IndexingMonitor` (found, rate, ETA), pill on every tab |
| Parsing | Filenames, captions, "post above" titles, Indian channel conventions | Partial | Parser v5 + context titles; repeated user reports of wrong titles; heuristics, not verified on real channels at scale |
| Library | Movies / series / seasons / episodes / versions | Working | `library` module, paging |
| | Untitled files without a poster hidden | Working | `LibraryQueryBuilder` (`includeUntitled`); **no place to see the hidden files** |
| | Availability states (in Telegram / local / inaccessible / offline) | Partial | Only `availableCount` (non-deleted index rows); "message gone" is found at play time |
| | Play button hidden for unavailable sources | Partial | Details checks `isAvailable`; availability is not re-checked against Telegram |
| | Playable-only filter | Missing | |
| Metadata | TMDB + Cinemeta matching with confidence | Working | `metadata:core`, `metadata:sync` |
| | Manual correction (Fix match) | Working | `FixMatchRoute`, match review screen |
| | TMDB reachable on DNS-blocked networks | Partial | Secure DNS fallback + `api.tmdb.org` failover (v0.10.0); **unverified on Jio**, SNI blocking not handled |
| | Built-in TMDB key | Unknown | Optional CI secret; whether it is set is not visible from the repo |
| Playback (Telegram) | Range streaming while downloading, seeking | Working (simulated) | `TelegramFileStreamer`, `TelegramMediaDataSource`; tests with simulated transfers |
| | Network loss / recovery, version fallback | Working (simulated) | `PlaybackRecoveryPolicy`, `TelegramLoadErrorHandlingPolicy` |
| | Adaptive buffering, next-episode prefetch | Working (simulated) | `playback:adaptive` benchmarks on a virtual player |
| | Audio/subtitle selection and memory | Working | `playback:tracks`, `Media3Tracks` |
| | FFmpeg audio (AC-3, DTS, TrueHD) | Working (build) | Jellyfin decoder; not device-verified |
| | Position save and restore | Working | `TelegramPlaybackController.saveProgress` (periodic + pause/stop/end) |
| | Background audio, media notification, lock-screen controls | Missing | No `MediaSession`/`MediaSessionService`, no foreground service; only picture-in-picture |
| | Process recreation in the player | Partial | Route args restore the title; position comes from saved progress; untested |
| | Real-device validation | Missing | `docs/AUDIT.md` §3 "Not verified on a device" still holds |
| Add-ons / web streams | Stremio/Nuvio add-ons, HTTP/HLS/DASH playback | Working (simulated) | `catalog:stremio`, `WebStreams`; no progress saved for add-on streams |
| | Torrent streaming | Working (local peer test) | `TorrentEngineLocalTest` against a real libtorrent seed on localhost; **on by default** |
| Search | Offline fuzzy/transliteration search, assistant phrases | Working | `search:*`, `assistant:*`, benchmarks |
| Watch | Continue watching, next episode, recently added | Working | `watch:*` |
| Sync | Encrypted progress sync via Saved Messages | Working (simulated) | `sync` module, Keystore-wrapped key |
| UI | Home, Search, Library, Settings dock; Movies/Series via Home | Working | `Dock.kt`, `Routes.kt` (`TopLevel`) |
| | First-run onboarding | Missing | No welcome / guided setup; Home shows a setup panel |
| | Dark/light themes, manga/classic styles, AMOLED | Working | `Theme.kt` |
| | Reduced motion | Missing | Animations ignore the system "remove animations" setting |
| | Android TV layout | Partial | TV layout, leanback launcher, D-pad focus; not device-verified |
| Storage | Image cache clear | Working | Settings → Advanced |
| | TDLib file cache visibility / limits | Partial | `StreamCache` trims; no size shown, no user limit, no clear button |
| | Web stream cache (512 MB) / torrent cache | Partial | Bounded, not visible or clearable |
| Local media | MediaStore / SAF sources | Missing | No code |
| Updates | In-app updater from GitHub releases | Working | `GitHubAppUpdates`; per-ABI APK; no checksum check (Android verifies the signature) |
| Diagnostics | Diagnostics screen | Docs only | `docs/ROBUSTNESS.md` describes it; the screen was removed with the developer tools |
| Release | Signed release builds | Partial | Without the `RELEASE_KEYSTORE_*` secrets the workflow signs with a throwaway key, and updates then fail to install over the previous version |
| | Code shrinking | Missing | `isMinifyEnabled = false`; universal APK 80 MB |
| | CI tests on push / PR | Missing | Only `release.yml`; it does not run tests or lint |

---

## 3. Top 20 user-facing problems

Ordered by how often and how badly they hurt a normal user.

| # | Problem | Where |
|---|---|---|
| 1 | **Wrong or missing titles** for files from Indian channels ("Tel + Hin 450MB"), the most repeated complaint | `media:filename`, `ContextFinder` |
| 2 | **Posters missing** when TMDB is blocked or no TMDB key is set (Cinemeta alone matches Indian titles poorly) | metadata, `Images.kt` |
| 3 | **TMDB not reachable on some Indian networks** while other apps work; the fix (v0.10.0) is unverified there | `TmdbProvider`, `SecureDns` |
| 4 | **No first-run guidance**: a new user lands on Home with a setup panel; what "sources" are and what indexing does is not explained | Home, Account, Sources |
| 5 | **Playback stops when the screen turns off or the app is left** (no background audio, no notification controls) | no `MediaSession` |
| 6 | **Logging out leaves the old library**; a new account sees titles it cannot play | logout path |
| 7 | **Files can disappear from view**: untitled files without a poster are hidden with no "unmatched files" list | `LibraryQueryBuilder` |
| 8 | **Indexing can't be paused or cancelled** from any screen; it only stops by itself | indexing UI |
| 9 | **No Wi-Fi-only option** for indexing and metadata; it runs on mobile data | `IndexScheduler` |
| 10 | **Unavailable titles still look playable** until Play is pressed (deleted messages, revoked access) | library, Details |
| 11 | **Storage is invisible**: the TDLib cache, stream cache and torrent cache can't be seen or cleared | Settings → Advanced |
| 12 | **Torrent streaming is on by default**, exposing the IP address once a torrent result is played | `AppSettings.torrentStreaming` |
| 13 | **Movies and Series are not in the main navigation** on phones (only via Home "See all" / Library filters) | `DOCK_TABS` |
| 14 | **Error messages are often technical** ("Rejected by api.themoviedb.org (401)…", TDLib codes) | `HttpClients`, auth UI |
| 15 | **Add-on streams don't remember where you stopped** | `StreamPlayer` |
| 16 | **Large updates (80 MB universal APK)** on slow networks; the per-ABI APK helps only through the updater | no R8, native libs |
| 17 | **Updates can fail to install** if a release is signed with a throwaway key (release-key secrets optional) | `release.yml` |
| 18 | **No reduced-motion support**; animated dock, spinners and transitions always run | Compose UI |
| 19 | **Settings mixes core and niche options** (add-ons, torrents, decoders, secure DNS next to basics) | Settings |
| 20 | **README describes an older app** ("plain debug screens and a minimal test player"), confusing new contributors and users | `README.md` |

---

## 4. Top 10 technical risks

| # | Risk | Impact | Where |
|---|---|---|---|
| 1 | **No real-device or real-Telegram validation** of streaming, indexing, auth | Shipping regressions invisible to the JVM suite | whole app |
| 2 | **No CI running tests or lint** | Broken builds and failing tests reach releases | `.github/workflows` |
| 3 | **Telegram-specific schema** blocks local media and other sources without a migration | Phase 8 cost, risk of data loss in migration | `core:database` |
| 4 | **WorkManager-only indexing** (8-minute runs, no foreground service) is slow on 100k-message channels and deferred by Doze | Very long first indexing | `IndexChatWorker` |
| 5 | **Flaky Hilt/KSP cold builds** (missing-binding errors that vanish on `--rerun-tasks`) | Unreliable local and CI builds | build setup |
| 6 | **Native code size and attack surface** (TDLib, libtorrent, FFmpeg) without R8 | APK size, memory, update cost | `app/build.gradle.kts` |
| 7 | **Several unparented coroutine scopes** | Work outliving its owner, unclear shutdown | various singletons |
| 8 | **Heuristic parser tuned to user screenshots** | Fixes for one channel break another (seen with "Ben 10", "1917") | `media:filename` |
| 9 | **Third-party services in the data path** (wsrv.nl, i0.wp.com image proxies; Cloudflare/Google DoH; add-on servers) | Availability and privacy depend on them | `Images.kt`, `SecureDns.kt`, `catalog:*` |
| 10 | **Release key handling is optional** | A key mismatch strands users on old versions | `release.yml` |

---

## 5. Security and privacy findings

Severity: Critical / High / Medium / Low. None found is Critical (no secret leak, no exported
attack surface, no cleartext credential handling).

| Severity | Finding | Evidence | Remediation |
|---|---|---|---|
| High | **Logout does not remove local data**: library (chat titles, file names), watch history, search index, caches, TMDB key and sync state stay; the next account inherits them | `DefaultTelegramAuthManager.logOut`, no data wipe | Clean logout: wipe Room tables tied to the account, TDLib files, stream/torrent/image caches, account prefs; keep only device settings |
| High | **Torrent streaming enabled by default**: peers see the user's IP address | `AppSettings.torrentStreaming = true` | Off by default; explicit opt-in with the warning shown at the moment of enabling |
| Medium | **Logcat in release builds is not redacted** (`Log.i/w/e` get raw messages; only stored lines go through `Redactor`) | `AppLogger` | Redact before `Log.*`, or drop info logs in release |
| Medium | **TDLib database not encrypted** (session keys rely on the app sandbox) | `TelegramConfig.databaseEncryptionKey` empty | Keystore-wrapped random key passed to TDLib; migration for existing sessions |
| Medium | **Poster URLs sent to third-party proxies** (wsrv.nl, i0.wp.com) when TMDB images are blocked: reveals which titles the user browses | `ui/core/Images.kt` `ImageRouting` | Disclose in Settings and allow turning the fallback off |
| Medium | **No `CLEARTEXT` restriction**: `cleartextTrafficPermitted="true"` app-wide (needed for some add-on streams) | `network_security_config.xml` | Keep cleartext only for user-added add-on/stream hosts; HTTPS for everything else is already the case in code, enforce it |
| Medium | **Releases may be signed with a throwaway key** | `release.yml` "Release signing key (optional)" | Make the release key required for tagged releases |
| Low | TMDB user key stored in plain SharedPreferences | `metadata/sync/TmdbKeys.kt` | Keystore-wrap like the sync key |
| Low | In-app updater doesn't verify `SHA256SUMS.txt` (Android does verify the signature on install) | `GitHubAppUpdates` | Verify the checksum before handing the APK to the installer |
| Low | `REQUEST_INSTALL_PACKAGES` permission (needed for the updater) blocks a Play Store listing | manifest | Keep for GitHub builds; a Play flavor without it if needed |
| Info | What leaves the device for metadata: parsed **title, year, type, language** only (`MetadataQuery`); no message text, chat names or file names | `metadata/api/MetadataModels.kt` | Document it in the app and in `docs/SECURITY_REVIEW.md` |
| Info | Exported components: only the launcher activity; receiver and providers not exported; no deep links, no WebView | manifest, code search | — |

---

## 6. Prioritized backlog

### P0 — release blockers

1. **CI**: a workflow that runs `testDebugUnitTest test`, lint and `assembleDebug` on every push
   and pull request; the release workflow runs tests before publishing and requires the release
   key.
2. **Clean logout and account switching**: wipe account data and caches on logout (High
   finding); clear explanation when a session expires.
3. **Torrent streaming off by default**, enabled only with an explicit consent dialog.
4. **Real-device validation pass** of sign-in, indexing a large channel, playback (MKV, multiple
   audio tracks, seeking, network loss) with a written, reproducible checklist; results recorded
   in `docs/IMPLEMENTATION_PROGRESS.md`.
5. **Background playback**: `MediaSessionService` with notification and lock-screen controls
   (users expect a media app to keep playing audio with the screen off).
6. **Flaky Hilt/KSP build**: find the cause and fix it so cold builds are reliable.
7. **Hidden files must be findable**: an "Unmatched files" view (and fix match from there).

### P1 — needed for a good public release

8. First-run flow: welcome → sign in → choose chats → indexing progress → library, in plain
   language.
9. Indexing controls: pause / resume / cancel per chat; Wi-Fi-only and battery-aware options;
   explicit states including CONNECTING and RECOVERING.
10. Availability states in the library (in Telegram, deleted, inaccessible, downloaded) and a
    "playable only" filter.
11. Storage screen: sizes of the TDLib, stream, torrent and image caches, limits, clear buttons.
12. Plain-language error messages for auth, TMDB and playback failures.
13. Movies and Series in the main navigation (phone).
14. Redact logcat; Keystore-encrypt the TMDB key; TDLib database encryption.
15. Disclose and allow disabling third-party image proxies and DoH.
16. R8 shrinking for release builds (with keep rules for TDLib, libtorrent, Media3 FFmpeg).
17. `docs/SECURITY_REVIEW.md`; README rewritten for the current app.
18. Reduced-motion support.

### P2 — after P0/P1 are stable

19. Media-source abstraction + migration, then local media (MediaStore / SAF).
20. Progress for add-on streams.
21. Foreground-service indexing for very large first runs (with a visible notification).
22. Unify network monitors and coroutine scopes; drop redundant FTS tables.
23. Split `ui:screens` by feature.
24. Optional: SMB/WebDAV, offline downloads, TV polish.

---

## 7. Implementation phases

Mapped to the requested order; each phase ends with build, tests, lint, docs and a progress entry.

| Phase | Scope | Backlog items |
|---|---|---|
| 1. Critical fixes | CI, release signing, flaky build, torrent default, logcat redaction | 1, 3, 6, part of 14 |
| 2. Telegram auth | clean logout / account switch, session-expired handling, clearer errors, TDLib db encryption | 2, 12 (auth), 14 |
| 3. Indexing | pause/resume/cancel, explicit states, Wi-Fi-only/battery options | 9 |
| 4. Streaming | MediaSession + background playback, process recreation, device validation checklist | 4, 5 |
| 5. Metadata & library | availability states, playable-only filter, unmatched files view | 7, 10 |
| 6. UI/UX | first-run flow, Movies/Series navigation, reduced motion, settings simplification | 8, 13, 18 |
| 7. Performance & security | storage screen, R8, proxy/DoH disclosure, `SECURITY_REVIEW.md` | 11, 15, 16, 17 |
| 8. Local media | source abstraction, migration plan, MediaStore/SAF | 19 |
| 9. Advanced (optional) | TV, SMB/WebDAV, downloads, add-on progress | 20–24 |
| 10. Release readiness | full test pass, docs, signed release, install-over-previous check | — |

---

## 8. Static analysis

`./gradlew :app:lintDebug` (11 Nov 2026): **0 errors, 14 warnings**. Report:
`app/build/reports/lint-results-debug.html`.

| Count | Check | Note |
|---|---|---|
| 3 | ObsoleteSdkInt | version checks below minSdk 26, dead code |
| 2 | UseKtx | style |
| 2 | AndroidGradlePluginVersion | newer AGP available |
| 1 | OldTargetApi | targetSdk 36, a newer one exists |
| 1 | InsecureBaseConfiguration | cleartext allowed app-wide (see §5) |
| 1 | PictureInPictureIssue | PiP setup hint |
| 1 | UsableSpace | free-space query |
| 1 | ApplySharedPref | `commit()` on the main path (`forgetLegacyCredentials`) |
| 1 | VectorRaster | a large vector drawable |
| 1 | other | see report |

Not run: detekt/ktlint (not configured in the repository), dependency vulnerability scanning
(no tool configured; to be added in Phase 7).

---

## 9. Documentation drift

| Document | Says | Code |
|---|---|---|
| `README.md` | "Phase 10 done", "plain debug screens and a minimal test player", Stage 1/Stage 2 | Full Compose UI shipped; debug screens removed |
| `docs/AUDIT.md` §5.6 | app module hosts ~3k lines of debug screens | `app` is 1.1k lines, no debug screens |
| `docs/ROBUSTNESS.md` | a diagnostics screen | no diagnostics screen in the UI (removed with the developer tools) |
| `README.md` | a Favorites list | the Favorites page was removed; favorite data and a card action remain (`TileActions.kt`, `LibraryRepositories.kt`) |

---

## 10. Files likely to change

| Phase | Files |
|---|---|
| 1 | `.github/workflows/release.yml`, new `.github/workflows/ci.yml`, `app/build.gradle.kts`, `app/src/main/kotlin/dev/yourstream/log/AppLogger.kt`, `ui/screens/.../settings/AppPreferences.kt`, `ui/screens/.../settings/SettingsCatalog.kt` |
| 2 | `telegram/core/.../auth/DefaultTelegramAuthManager.kt`, `telegram/core/.../manager/DefaultTelegramManager.kt`, `app/src/main/kotlin/dev/yourstream/di/AppModule.kt`, a new account-data cleaner (library/index/metadata DAOs, caches), `ui/screens/.../settings/Account.kt`, `metadata/sync/.../TmdbKeys.kt` |
| 3 | `media/api/.../model/IndexModels.kt`, `media/indexer/.../work/IndexScheduler.kt`, `IndexChatWorker.kt`, `media/indexer/.../DefaultMediaIndexer.kt`, `ui/screens/.../IndexingMonitor.kt`, `ui/screens/.../navigation/Dock.kt` (pill), library status screen |
| 4 | new `playback/media3/.../PlaybackService.kt`, `app/src/main/AndroidManifest.xml` (service, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`), `TelegramPlaybackController.kt`, `ui/screens/.../player/*`, `docs/PLAYBACK.md` |
| 5 | `library/.../api/LibraryModels.kt`, `LibraryQueryBuilder.kt`, `core/database` (availability column + migration), `ui/screens/.../details/Details.kt`, browse screens |
| 6 | `ui/screens/.../navigation/*`, new onboarding screens, `HomeScreen.kt`, `ui/core` motion helpers |
| 7 | `ui/screens/.../settings/*` (storage page), `ui/core/.../Images.kt`, `core/network/.../SecureDns.kt`, `app/build.gradle.kts` (R8 + rules), `docs/SECURITY_REVIEW.md` |
| 8 | `core/database` (source column, migration 12→13), `media:api`, `library`, `playback:media3` (local `DataSource`), new `media:local` module |
