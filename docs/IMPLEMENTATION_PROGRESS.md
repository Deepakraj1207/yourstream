# Implementation progress

Tracks the phased plan from [PRODUCT_AUDIT.md](PRODUCT_AUDIT.md). Statuses: NOT STARTED,
IN PROGRESS, COMPLETED, BLOCKED, NEEDS REAL-DEVICE TESTING. A phase is COMPLETED only when all
of its items are done and verified as stated; anything verified only on the JVM is marked so.

For a new conversation: read this file and `docs/PRODUCT_AUDIT.md` first; they are the state.

| Phase | Status |
|---|---|
| 0. Repository audit | COMPLETED |
| 1. Critical fixes | COMPLETED (one item moved to monitored risks, see below) |
| 2. Telegram authentication | NEEDS REAL-DEVICE TESTING |
| 3. Media indexing | NEEDS REAL-DEVICE TESTING |
| 4. Streaming engine | NEEDS REAL-DEVICE TESTING |
| 5. Metadata and library | COMPLETED |
| 6. UI/UX | NEEDS REAL-DEVICE TESTING |
| 7. Performance and security | NEEDS REAL-DEVICE TESTING |
| 8. Local media | NOT STARTED |
| 9. Advanced features | NOT STARTED |
| 10. Release readiness | NOT STARTED |

Decisions taken (user said "proceed" on the audit's recommendations, 11 Nov 2026):
add-ons and torrents stay as optional extras, torrents off by default. Phase 6 (user said "Yes",
12 Nov 2026): the glass dock is replaced by a plain Material 3 navigation bar with Movies and
Series added.

---

## Phase 0 — Repository audit · COMPLETED

* **Objective:** understand the code as it is; no source changes.
* **Result:** `docs/PRODUCT_AUDIT.md` (architecture, feature matrix, top problems and risks,
  security findings, P0/P1/P2 backlog, phases, files to change).
* **Tests run:** full JVM suite (pass), `:app:assembleDebug` (pass), `:app:lintDebug`
  (0 errors, 14 warnings).

---

## Phase 1 — Critical fixes · COMPLETED

**Objective:** stop shipping regressions and close the release-blocking risks that need no
product redesign.

**Existing problems (from the audit):** no CI running tests or lint; releases could be signed
with a throwaway key; torrent streaming on by default without consent (IP exposure); release
logcat not redacted; intermittent Hilt/KSP "missing binding" build failures.

| Item | Status | Change |
|---|---|---|
| CI on every push / PR | COMPLETED (first GitHub run [43063230121](https://github.com/Deepakraj1207/yourstream/actions/runs/43063230121) passed in about 8 min) | `.github/workflows/ci.yml`: `test`, `:app:lintDebug`, `:app:assembleDebug`; reports uploaded on failure |
| Releases tested and signed with the release key | COMPLETED (takes effect on the next release) | `release.yml`: tests before building; fails without `RELEASE_KEYSTORE_BASE64`; every APK's certificate checked against the pinned SHA-256 (`35249306…07b2`, the key that signed v0.10.0, read from the published APK with `apksigner`) |
| Torrents off by default, with consent | COMPLETED | `AppSettings.torrentStreaming = false` (stored default too); `TorrentConsentDialog` before turning on, from Settings or from a torrent result; torrent rows stay tappable while off |
| Logcat redaction | COMPLETED | `AppLogger.forLogcat`: logcat gets redacted text; release builds log no stack traces (type + redacted message instead) |
| Intermittent Hilt/KSP build failure | NOT REPRODUCED — moved to monitored risks | see below |

**Files modified:** `.github/workflows/ci.yml` (new), `.github/workflows/release.yml`,
`app/src/main/kotlin/dev/yourstream/log/AppLogger.kt`,
`ui/screens/.../settings/AppPreferences.kt`, `SettingsCatalog.kt`, `Settings.kt`,
`ui/screens/.../addon/CatalogTitle.kt`, `ui/screens/src/main/res/values/strings.xml`,
`docs/STREAMS.md`; tests: `app/src/test/.../log/LogcatRedactionTest.kt` (new),
`ui/screens/src/test/.../ScreensTest.kt` (`torrentStreamingIsOffByDefaultAndAsksBeforeTurningOn`).

**Tests run:**
* `:app:testDebugUnitTest` (new `LogcatRedactionTest`) and `:ui:screens` `ScreensTest`
  (including the new consent test): pass.
* Cold, cache-less run of the CI command locally (`./gradlew clean`, then
  `./gradlew --no-build-cache --continue test :app:lintDebug :app:assembleDebug`): pass, 4 min 57 s.
* GitHub CI, first run on commit `b12d553`: pass.

**Hilt/KSP build failure — not reproduced, monitored.** Every "missing" binding in the one
failed build came from a single Hilt module (`playback/media3/PlaybackModule.kt`), in a run that
followed edits to that module. Four reproduction attempts all built correctly: incremental
build after a comment-only change; after adding a top-level class; a cold cache-less run of the
CI command; and an exact replay of the failing sequence (a new top-level `object` and class in
`playback:media3`, then `./gradlew testDebugUnitTest test --continue`). No root cause is
proven, so nothing was changed for it. Mitigations in place: the release build retries once with
`--rerun-tasks`, and CI now reports any recurrence with its log. Reopen with the CI log if it
happens again.

**Update (Phase 2): it recurred in CI.** Push run 3 on `a91d9e8` failed (the same Hilt missing
bindings from `playback/media3/PlaybackModule.kt`, and failing `:playback:media3` unit tests);
its re-run failed the same way; the pull-request run of the same code passed, as did both runs
of the next commit. All four runs restored the same 117 task outputs from the build cache, so the
cache contents are not the difference. Tried and not reproduced locally: a cold build with
the cache filled by the previous commit (passed, 17 s). The failing test names were only in an
artifact this session cannot download, so CI now prints them (and the module's generated Hilt
metadata and compiled classes) in a "Failure summary" step.

**Update 2 — evidence and fix attempt.** The push run of `5dcc747` failed again (and its re-run).
The new summary showed: no failing tests, and `playback:media3`'s Hilt metadata present and
compiled. The missing bindings this time came from *other* modules (`catalog:sync`:
`CatalogCache`, `LocalCatalogSource`, `LibraryAvailability`, `ExternalMetadataFallback`;
`assistant:engine`: `MediaAssistant`). So the app's Hilt aggregation intermittently sees an
incomplete set of library classes, whichever modules they are. All affected modules applied the
Hilt Gradle plugin, which adds a bytecode transform (`transformDebugClassesWithAsm`) whose
output replaces each library's classes for consumers. Libraries need that plugin only for
`@AndroidEntryPoint` classes, and only `app` has any (`MainActivity`, `YourStreamApp`,
`GitHubAppUpdates`' receiver). **Change:** the plugin is removed from the 12 library modules
(KSP `hilt-compiler` still generates their modules and factories); only `app` applies it. Clean
cache-less build, all tests, lint and APK pass. **Status: fix applied, being verified by repeated
CI runs** (an intermittent failure needs several green runs, not one).

**Update 3 — two separate causes, both fixed.** The push run of `6d885d7` (plugin removed) had
no Hilt error any more; it failed only on one test, which the new summary named:
`TelegramPlaybackTest > releaseCancelsTheTelegramDownload` ("download cancelled on release").
Cause: CI has no ffmpeg-generated 85-minute sample, so the test falls back to a 296 KB clip,
which downloads completely at the simulated 2 MB/s before `release()` on a fast runner; nothing
is left to cancel (the app correctly cancels only unfinished downloads). Reproduced locally by
forcing the fallback: old test 3/3 fail, fixed test 3/3 pass. Fix (`56b484f`): the fallback gets
a speed at which the whole clip takes ~12 s. CI after both fixes: push and pull-request runs of
`56b484f` passed; a re-run of the push run was started to confirm.

**Update 4 — the Hilt part was not fixed by removing the plugin.** The next push run (`bd6cdfd`,
docs only) failed with missing bindings from yet another module (`media:indexer`:
`MediaIndexRepository`, `MediaIndexer`). Pattern over all runs: the module missing from the Hilt
graph differs every time; every failing build reused task outputs from the Gradle build cache
(CI push runs, and the one local failure); no build without the cache has failed (four cold
local builds, every release build, which uses `--no-build-cache`). **Change:**
`org.gradle.caching=false` in `gradle.properties` (CI and local). The plugin removal stays as a
cleanup (libraries have no `@AndroidEntryPoint`), but it is not the fix. The test fix stands on
its own (reproduced and verified). **Status: fix applied, needs several green push runs to be
called fixed.**

**Update 5 — not the cache either.** The push run of `06fc4ca` (cache off) failed the same way
(missing bindings, a different module again), so the cache change is reverted. Checked with
`--dry-run`: `:app:hiltAggregateDepsDebug` does depend on every library's
`bundleLibCompileToJarDebug`/`bundleLibRuntimeToJarDebug`, so it is not a missing task
dependency. Observations that remain: pull-request runs passed 6/6, push runs fail often and run
slower (≈10 vs 8 min); the build ran tests, lint and compilation in one parallel invocation with
a 4 GB Gradle daemon, a Kotlin daemon of the same size and test JVMs up to 3 GB on a 16 GB
runner. **Working hypothesis (unproven): memory pressure kills or degrades the Kotlin/KSP daemon
mid-build.** Change: CI builds the APK first, then runs tests, then lint (separate steps, so
compilation never competes with test JVMs), and the failure summary now reports daemon/OOM
messages, the kernel's OOM log and the Hilt metadata count in every library jar.

**Update 6 — the actual fault located.** With the APK built alone (no tests, no lint) a push run
still failed, so memory is ruled out (6 of 16 GB used, no OOM, no daemon crash). The new summary
showed the fault directly: the missing bindings (`WatchIntelligence`, `WatchStatistics`) belong
to `watch:engine`, and **`watch:engine`'s jar contained 0 Hilt metadata classes** (locally: 2).
So intermittently one library's KSP step produces no Hilt output, and the app's Hilt graph then
lacks that module — the app-side aggregation was never the problem. The module differs each
time, which points at KSP tasks running concurrently (KSP2 runs inside the Gradle process).
**Change:** a Gradle shared build service lets only one `ksp*Kotlin` task run at a time
(root `build.gradle.kts`); the failure summary now also prints the KSP-generated file count of
every module. **Status: fix applied; verifying with repeated push runs.**

**Result:** with KSP serialised, CI on `2708d76` passed 4 of 4 runs (the push run three times,
plus the pull-request run), every step green (APK, tests, lint). Before it, 7 of the previous
11 push runs had failed. Counted as fixed; the failure summary stays in CI, so a recurrence
would show the module and its KSP output at once. Cost: KSP steps no longer overlap (the APK
step took ≈4–5 min on CI). Earlier attempts that did not fix it, kept or reverted as noted:
library Hilt plugin removed (kept as a cleanup), build cache off (reverted), build/test/lint
split (kept: clearer CI, and tests no longer compete with compilation).

**Known limitations / remaining work:**
* Consent dialog and logcat redaction are verified by JVM/Robolectric tests, not on a device.
* The release-workflow changes (required key, certificate pin, tests first) run for the first
  time on the next release.
* Users who had torrents on implicitly (never touched the switch) find them off after updating
  and are asked once when they next play a torrent. Intended.

---

## Phase 2 — Telegram authentication · NEEDS REAL-DEVICE TESTING

**Objective:** a sign-in/sign-out flow a non-technical user can trust: clear errors, a clean
sign-out, safe account switching, an explained session loss.

**Inspected first:** `DefaultTelegramAuthManager`, `DefaultTelegramManager`,
`TelegramErrorMapper`, `Account.kt` (screen + ViewModel), `AppModule` (TDLib config), the Room
schema (35 tables), every SharedPreferences file, caches (Coil, web streams, torrents), workers,
diagnostics.

**Existing problems:** sign-out kept the library, history, search index, caches, sync key and
logs, so a second account saw the first one's titles; sign-out happened on one tap; a session
ended elsewhere left the user on the phone step with no explanation; flood waits, connection
problems and unknown TDLib errors were shown as raw text ("Too many requests, retry in 3600s",
"Telegram error 400: …"); the manual test script described debug screens that no longer exist.

**Already sound (kept):** every TDLib authorization state handled (code, 2FA, email, QR,
resend); built-in API credentials; session persistence in `noBackupFilesDir`; phone numbers
redacted in logs; one login request at a time.

| Item | Status | Change |
|---|---|---|
| Clean sign-out | COMPLETED (JVM) | `AccountSession` (ui:screens) + `DeviceAccountData` (app) + `AccountData` (core:database): on sign-out the account's rows, indexing work, posters, stream/torrent caches, sync key, logs/events/crash reports are removed; device settings, languages, add-ons, TMDB key kept. Every table not on the keep-list is treated as account data |
| Account switching | COMPLETED (JVM) | Rule "signed out ⇒ no account data": every sign-in passes through the signed-out state; leftovers from earlier versions or an interrupted cleanup are removed then |
| Session ended elsewhere / expired | COMPLETED (JVM) | Detected as signed-in → signed-out without the user's sign-out (also when the brief LoggingOut state is skipped); "You were signed out" on the sign-in screen until the next sign-in, kept across restarts |
| Sign-out confirmation | COMPLETED | Dialog saying what is removed and what stays; progress text while removing |
| Plain-language errors | COMPLETED | `authErrorText`: waits in seconds/minutes/hours, "Can't reach Telegram…", unknown TDLib errors with their code as a detail |
| TDLib database encryption | DECIDED AGAINST FOR NOW | Reason in `docs/TELEGRAM_SETUP.md` §3: file-based encryption + no backups already cover data at rest; a Keystore-held key adds a way to lose the session on devices with Keystore faults; revisit with device data |
| Manual test script | COMPLETED | `docs/TELEGRAM_SETUP.md` §5 rewritten for the current app, with sign-out, session-ended, account-switch and flood cases |

**Files modified:** `core/database/.../AccountData.kt` (new),
`core/diagnostics/.../EventJournal.kt` (`clear`), `playback/media3/.../WebStreams.kt`
(`clearCache`), `ui/screens/.../settings/AccountSession.kt` (new), `Account.kt`,
`ui/screens/src/main/res/values/strings.xml`, `app/.../account/DeviceAccountData.kt` (new),
`app/.../YourStreamApp.kt` (starts the session watcher), `app/build.gradle.kts`
(`playback:torrent` dependency), `docs/TELEGRAM_SETUP.md`.

**Tests added:** `AccountDataTest` (Robolectric, real schema: one row in every account table,
wipe empties all, FTS search finds nothing, language settings and catalog cache kept; empty
wipe harmless) · `AccountSessionTest` (7: user sign-out wipes without notice; ended elsewhere
wipes and explains, cleared on sign-in; skipped LoggingOut still counts; leftovers removed
while signed out; restart keeps everything; failed wipe retried; refused sign-out changes
nothing) · `ScreensTest` (confirmation dialog, signed-out notice, plain-language errors) ·
`ViewModelTest` updated for the new constructor and structured error.

**Tests run:** `./gradlew --continue test :app:lintDebug :app:assembleDebug`: pass; lint
0 errors, 14 warnings (unchanged). GitHub CI: the pull-request run of `a91d9e8` passed; the push
run failed twice with the intermittent Hilt/KSP problem (Phase 1, monitored risk), not with a
test of this phase; both runs of the next commit (`66a088d`) passed.

**Known limitations / remaining work:**
* **Not run against real Telegram.** TDLib's behaviour on sign-out (LoggingOut → Closed →
  WaitPhoneNumber) and on a session terminated from another device is taken from TDLib's
  documented states and the existing manager; steps 8–10 of the manual script in
  `docs/TELEGRAM_SETUP.md` verify it on a device.
* `DeviceAccountData` (WorkManager, Coil, stream cache, torrent engine, diagnostics) is only
  exercised by building; its database part is tested. A device run of step 8 checks the rest.
* Signing out while offline: TDLib's `logOut` needs the network to end the server session; the
  app shows "Signing out…" until it completes. Not changed (TDLib behaviour).

**Next:** Phase 3 — Media indexing (pause/resume/cancel, explicit states, Wi-Fi-only and
battery-aware scheduling).

---

## Phase 3 — Media indexing · NEEDS REAL-DEVICE TESTING

**Objective:** indexing the user can control and understand: pause, resume and stop, explicit
states, Wi-Fi-only and battery-aware scheduling, without re-indexing what is already indexed.

**Inspected first:** `IndexingEngine`, `IndexChatWorker`, `IndexScheduler`,
`DefaultMediaIndexer`, `IndexerInitializer`, `IndexProgressTracker`, `IndexModels`,
`IndexedChatEntity`/DAO, Library status screen, indexing pill, Settings.

**Already sound (kept, verified by the existing tests):** incremental head/backfill scanning with
per-filter cursors saved per page (resume after interruption, nothing scanned twice); duplicate
detection (unique chat+message, grouped versions); deleted-message detection (verify pass + live
deletions); retries with backoff and FLOOD_WAIT handling; live progress (found, rate, ETA).

**Existing problems:** no pause or resume, and stop was not reachable from any screen; states
were `RUNNING` plus an overloaded `PAUSED` (it meant "time budget used up"); a network error was
shown as `FAILED` although it would be retried; work ran on any network, the battery only
mattered for the periodic sync.

| Item | Status | Change |
|---|---|---|
| Explicit states | COMPLETED | `IndexState`: IDLE, QUEUED, CONNECTING, SCANNING, PROCESSING, COMPLETED, PAUSED, FAILED, CANCELLED, RECOVERING (+ `isActive`, `isPending`). QUEUED is kept: it is the honest state for waiting on a turn, Wi-Fi or the battery |
| Pause / resume (per chat, all) | COMPLETED (JVM) | `indexed_chat.paused` (DB v13, auto-migration, tested); `pause`, `resume`, `pauseAll`, `resumeAll`; scheduler, periodic sync and worker skip paused chats; a pause during a run ends it in PAUSED keeping its pages |
| Stop (cancel) | COMPLETED (JVM) | Reachable per running chat in Library status; ends in CANCELLED, next sync continues |
| Wi-Fi only / battery-aware | COMPLETED (JVM) | `IndexingPolicy` stored by `IndexScheduler` (workers read it in any process); WorkManager constraints UNMETERED / batteryNotLow; changing it reschedules waiting and running work; Settings → Content & discovery → Indexing |
| Recovery | COMPLETED (JVM) | Network errors → RECOVERING with the reason; FAILED only when the worker's retries run out; at start, states left by a killed process (and pre-v13 RUNNING) → RECOVERING, pre-v13 PAUSED → QUEUED |
| Plain-language status | COMPLETED | Library status per chat: "Connecting to Telegram…", "N files found so far", "Waiting for Wi-Fi", "Interrupted — continuing soon", "Paused", "Stopped — continues at the next sync"; Pause/Resume/Stop and Pause all |

**Files modified:** `media/api/.../IndexModels.kt`, `MediaIndex.kt`;
`media/indexer/.../DefaultMediaIndexer.kt`, `IndexerInitializer.kt`,
`engine/IndexingEngine.kt`, `engine/IndexProgressTracker.kt`, `work/IndexChatWorker.kt`,
`work/IndexScheduler.kt`, `store/IndexStore.kt`, `store/RoomIndexStore.kt`,
`store/EntityMappers.kt`, `build.gradle.kts` (work-testing); `core/database/.../Entities.kt`,
`YourStreamDatabase.kt` (v13), `DatabaseSafety.kt`, `dao/IndexedChatDao.kt`, `schemas/.../13.json`;
`ui/screens/.../library/LibraryStatus.kt`, `IndexingMonitor.kt`, `settings/Settings.kt`,
`settings/SettingsCatalog.kt`, `strings.xml`; `docs/INDEXING.md`.

**Tests added:** `IndexingControlsTest` (6, real Room + engine + WorkManager test mode: pausing an
idle chat holds it through schedule/scheduleAll and a queued run makes no Telegram request;
pausing a running scan keeps its pages and resume adds only the rest; stop ends only this run;
network failure is RECOVERING with the reason; the policy becomes the work's constraints and is
persisted; old and killed-process states are read sensibly and a pause survives start-up
recovery) · `MigrationTest.v12ToV13…` · `AdvancedScreensTest.sourcesShowTheirIndexingState…`
(state wording, Pause/Resume/Stop, Pause all) · `ScreensTest.indexingCanBeLimitedToWifi…`.
**Tests updated (meaning changed, not weakened):** `IndexingEngineTest` time-budget stop now
QUEUED (was PAUSED) and persistent network failure now RECOVERING (was FAILED), each with the
reason in the assertion; UI tests use SCANNING for the removed RUNNING; positional
`indexed_chat` fixtures in five modules gained the new column.

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass. Lint 0
errors, 15 warnings (+1: `NewerVersionAvailable` for kotlinx-serialization 1.13.0, a new upstream
release, not from this change).

**Known limitations / remaining work:**
* `IndexChatWorker` itself (paused skip, CONNECTING, FAILED after the last retry) is exercised
  only by compiling; WorkManager runs it on a device. Manual steps: `docs/INDEXING.md`.
* Indexing still runs in WorkManager's ~10-minute windows (no foreground service), so a first
  scan of a very large channel takes several windows (backlog item 21, P2).
* Live updates still apply to a paused chat (by design: they read nothing; noted in the docs).

**Next:** Phase 4 — Streaming engine (background playback with a media session, process
recreation, device validation checklist).

---

## Phase 4 — Streaming engine · NEEDS REAL-DEVICE TESTING

**Objective:** playback that behaves like a media app: keeps playing in the background with
system controls, recovers from network trouble and process death, and is validated on a device.

**Inspected first:** `TelegramPlaybackController`, `PlaybackRecoveryPolicy`,
`TelegramLoadErrorHandlingPolicy`, `TelegramFileStreamer`/`StreamingConfig`,
`LibraryMediaSourceResolver`, `WebStreams`, `PlayerViewModel`/`PlayerScreen`,
`StreamPlayerViewModel`, the manifest, `PlaybackSessionStore`, the existing playback tests.

**Already sound (kept; covered by the existing simulated-Telegram tests):** range streaming
while downloading, seek jumps the download window, adaptive buffering and prefetch, network-loss
recovery with backoff and version fallback, offline wait bounded (`maxOfflineWaitMs` 120 s — the
audit's "no deadline" item is not accurate), track selection and memory, FFmpeg audio, position
saving (periodic + pause/stop/end) and restore after process death (`PlaybackSessionStore`).

**Existing problems:** no media session: no notification or lock-screen controls, no
headset buttons, no foreground service, and the library player paused on every `ON_STOP`, so
playback stopped with the screen off; the offline message was raw text ("waiting for network").

| Item | Status | Change |
|---|---|---|
| Background playback + system controls | COMPLETED (JVM) | `ActivePlayback` + `TitledPlayer` (`playback:media3`), `PlaybackService` (app, Media3 `MediaSessionService`, `foregroundServiceType=mediaPlayback`); both players register/unregister; started from `YourStreamApp` when a player registers |
| Setting | COMPLETED | Settings → Playback → *Keep playing in the background* (on); off restores pause-on-leave in both players |
| Session privacy | COMPLETED | Service not exported; `onConnect` accepts only the media notification, trusted system controllers and this app; swipe-away stops playback |
| Offline message | COMPLETED | "No connection. Playback continues when you're back online." |
| Real-device checklist | COMPLETED (written) | `docs/PLAYBACK.md` §9b: 18 checks (seek in > 2 GB MKV, tracks, sync, MP4 index at end, airplane mode, Wi-Fi→mobile, background, lock screen, headset, swipe-away, notification tap, don't-keep-activities, process kill, next episode, deleted message) |

**Files modified:** `gradle/libs.versions.toml` (`media3-session`, same version 1.11.1),
`app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` (service, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`), `app/.../playback/PlaybackService.kt` (new),
`app/.../YourStreamApp.kt`, `playback/media3/.../ActivePlayback.kt` (new),
`playback/media3/build.gradle.kts` (test dep), `ui/screens/.../player/Player.kt`,
`PlayerScreen.kt`, `addon/StreamPlayer.kt`, `settings/AppPreferences.kt`, `SettingsCatalog.kt`,
`strings.xml`, `docs/PLAYBACK.md`.

**Tests added:** `ActivePlaybackTest` (Robolectric: registration rules incl. a stale detach; a real
`MediaController` on a real `MediaSession` sees the title/subtitle and its play/pause drive the
ExoPlayer) · `ScreensTest.backgroundPlaybackIsOnByDefaultAndCanBeTurnedOff`.

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass; lint 0
errors, 15 warnings (unchanged). Merged manifest checked: service not exported, media playback
type, both permissions.

**Known limitations / remaining work:**
* **Real Telegram playback is not validated** in this phase either: all playback tests use a
  simulated Telegram file source. The checklist in `docs/PLAYBACK.md` §9b is the validation; its
  results go here when run.
* `PlaybackService` (foreground notification, lock screen, swipe-away) runs only on a device;
  only the session/controller path is tested.
* No Android 13+ notification-permission prompt: media-session notifications are exempt from it
  (Android behaviour; to confirm on a device, checklist item 9).
* Add-on streams still save no progress (backlog item 20, P2).

**Next:** Phase 5 — Metadata and library (availability states, playable-only filter, a view for
unmatched/hidden files).

---

## Phase 5 — Metadata and library · COMPLETED

**Objective:** every title's availability and match state is visible; nothing is lost from view;
no Play button for something that cannot play.

**Inspected first:** `LibraryModels`, `LibraryQueryBuilder`, `LibrarySynchronizer` (what is kept
when files go), `LibraryRepositories`, `DetailsViewModel`/`DetailsScreen`, `Presentation` and
`tileCard`, browse view models and screen, `LibraryStatus`, watch next-episode rules, Fix match
(`LibraryMetadataService`), the media download state.

**Already sound (kept):** `available_count` per title; browsing hides titles with no files;
titles with no files and no user data are deleted by the sync; Details already had no Play for
an unavailable movie; next-episode logic skips episodes with no file; Fix match exists (videos
are searched as movies, so it also names untitled files); download state is kept current from
TDLib file updates.

**Existing problems:** titles kept for their user data (e.g. in "My library") vanished from view
when their files were deleted, with no explanation; Details showed an empty space where Play was;
nothing said whether a title was matched or only parsed from the file name; downloaded files were
not marked; files with no title and no poster (hidden since 0.10) could not be found anywhere.

| Item | Status | Change |
|---|---|---|
| Playable-only filter | COMPLETED | Library tab chip "Playable only" (on); off lists removed titles (`BrowseFilters.playableOnly` → `includeUnavailable`, survives process death) |
| Removed titles marked | COMPLETED | Card caption/badge "Removed", opens Details (never plays); Details explains and shows no Play |
| Match state | COMPLETED | Details "Not matched… use Fix match" when no catalogue match (not shown for personal videos) |
| Downloaded | COMPLETED | Version row "On this device" when Telegram has the whole file |
| Hidden untitled files | COMPLETED | `LibraryQuery.untitledOnly`; Library status → "N files without a title" → list (`UntitledFilesRoute`) with Play and Fix match |
| Availability reference | COMPLETED | `docs/LIBRARY.md` "Availability" |

**Files modified:** `library/.../api/LibraryModels.kt`, `data/LibraryQueryBuilder.kt`;
`ui/screens/.../browse/BrowseContract.kt`, `BrowseViewModels.kt`, `BrowseScreen.kt`;
`Presentation.kt`, `TileActions.kt`; `details/Details.kt`, `DetailsScreen.kt`;
`library/LibraryStatus.kt`; `navigation/Routes.kt`, `AppNavigator.kt`, `AppNavHost.kt`;
`strings.xml`; `docs/LIBRARY.md`.

**Tests added:** `LibraryAvailabilityTest` (real Room + library sync: the untitled list shows
exactly what browsing hides; a title in "My library" whose only file is deleted stays, marked
unavailable, listed only with "playable only" off) · `ScreensTest` (removed title: no Play and an
explanation; not-matched line; "On this device"; the Playable-only chip and its query mapping) ·
`AdvancedTest` (removed caption and Details target; Library status opens the untitled list).

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass; lint 0
errors, 15 warnings (unchanged).

**Known limitations:** "On this device" reflects TDLib's last file update (a file deleted from
TDLib's cache while the app was closed shows correctly only after TDLib reports it); a chat that
became inaccessible is shown per chat in Library status, not per title.

**Next:** Phase 6 — UI/UX (first-run flow, Movies/Series in the main navigation, reduced motion,
dock decision).


---

## Phase 6 — UI/UX · NEEDS REAL-DEVICE TESTING

**Objective:** simple navigation (Home, Movies, Series, Search, Library, Settings); a guided first
run that hides API ids, hashes and provider internals; Material 3, dark and light, reusable
components, loading/empty/error states, accessible touch targets, reduced motion, no glass
effects.

**Inspected first:** `AppShell`/`AdaptiveShell`, `Dock.kt`, `Routes.kt` (`TopLevel`),
`AppNavigator`, `AppNavHost` (transitions), Home's setup panel and banner, Account and Sources
screens, `IndexingMonitor`, `Skeleton`, `Focus`, every `infiniteRepeatable`/`AnimatedVisibility`,
user-visible strings for credentials (none: the API id and hash are build config, never shown),
the screen tests and screenshots.

**Already sound (kept):** Material 3 throughout with dark, light, AMOLED and manga styles;
shared components (`MediaRow`, `ListRow`, `EmptyState`, `ErrorState`, skeletons) with loading,
empty and error states on Home, browse, Search and Details (existing tests); Compose already
shortens finite animations by the system animation scale.

**Existing problems:** the phone dock had four tabs (Movies and Series only via Home or the
Library filter) and a glass look the brief rules out; no first-run guidance; looping animations
(skeleton pulse, indexing spinner) and sliding/zooming page transitions ignored "Remove
animations", and there was no app-level option.

| Item | Status | Change |
|---|---|---|
| Six-tab navigation | COMPLETED | `MAIN_TABS` on every layout; phone: plain M3 `NavigationBar` under the content (glass dock and `Dock.kt` removed, indexing pill moved to `IndexingPill.kt`); Continue watching / Recently added count as Home; tab names option kept, names stay in the accessibility tree |
| Guided first run | COMPLETED (JVM-verified) | `firstrun/FirstRun.kt`: 6 steps, skip on every step, links to the real sign-in and chat screens, live indexing progress; shown on first launch only (`FirstRunStore`), not to updating users who already read chats; Settings → About → Setup guide reopens it; full screen on every layout |
| Reduced motion | COMPLETED (JVM-verified) | `ui/core/Motion.kt` `LocalReducedMotion` = system "Remove animations" (observed live) or Settings → Appearance → Reduce motion: cross-fade transitions, still skeletons, no spinner, no focus lift |
| Touch targets | COMPLETED (JVM-verified) | New check: every clickable node ≥ 48 × 48 dp on Home, Search, Details, Settings, the navigation and the first run. It found the six tabs at 46–47 dp on a 320 dp screen (M3 `NavigationBar`'s 8 dp item gaps); fixed by building the bar from M3's container and items without the gaps (≥ 53 dp) |
| No glass / excessive gradients | COMPLETED | Glass dock removed; remaining gradients are text scrims over artwork and the title placeholder |
| Polished Home / Details / Search / Player | Descoped by the user (12 Nov 2026: "proceed" after being asked) | Existing screens kept as they are, with their loading, empty and error states; no redesign |

**Files modified:** `ui/core/.../Motion.kt` (new), `Skeleton.kt`, `Focus.kt`;
`ui/screens/.../navigation/AppShell.kt`, `IndexingPill.kt` (was `Dock.kt`), `Routes.kt`,
`AppNavigator.kt`, `AppNavHost.kt`, `ShellViewModel.kt`; `firstrun/FirstRun.kt` (new);
`settings/AppPreferences.kt`, `SettingsCatalog.kt`, `Settings.kt`; `strings.xml`; `docs/UI.md`.

**Tests added:** `FirstRunTest` (all six steps with live state changes; back and skip; Telegram
step while starting or unavailable shows no ids or reasons; privacy text; shown once / not to
set-up users / kept across restarts; finishing returns Home from first launch and from Settings,
on a real `NavHost`) · `ReducedMotionTest` (system animation scale read and followed live) ·
`ScreensTest` (six tabs on every layout, Home selected on Continue/Recent pages, tab names kept
for screen readers without labels, Reduce motion toggle, 48 dp touch targets on Home, Search,
Details, Settings and the navigation) · screenshots: first-run welcome, Telegram (light),
indexing, playback; all phone screenshots re-rendered with the new bar.

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass (569 JVM
tests in the result files, 0 failures); lint 0 errors, 15 warnings (unchanged). Screenshots
re-rendered (41) and reviewed: home, library (dark and manga), first-run welcome and Telegram.

**Known limitations / untested:** the first run, the new bar and reduced motion are verified on
Robolectric only; no device, TalkBack or TV pass yet. On a 320 dp screen longer tab names in
other languages may be shortened with an ellipsis (English fits). Home, Details, Search and Player
were not redesigned (descoped by the user).

**CI:** push run 29 and pull-request run 30 passed on `8f602df`.

**Status:** NEEDS REAL-DEVICE TESTING: on a phone, run the first launch end to end (skip, and
connect → choose chats → indexing), check the bar at the largest display size, TalkBack across
the tabs and first run, and "Remove animations" on and off.


---

## Phase 7 — Performance and security · NEEDS REAL-DEVICE TESTING

**Objective:** close the audit's remaining security and privacy findings, make storage visible and
clearable, prepare R8, and write `docs/SECURITY_REVIEW.md`.

**Inspected first:** `ImageRouting` and `SecureDns` (who sees what), `TmdbKeys` and the sync
key's Keystore code, `GitHubAppUpdates` and the release workflow's `SHA256SUMS.txt`, every cache
directory and its owner (`AppModule`, `WebStreams`, `TorrentEngine`, `appImageLoader`,
`StreamCache`), the TDLib wrapper's `optimizeStorage`, the release build type, the JNI symbols of
the three native libraries, every manually created `CoroutineScope`, the manifest, backup rules
and network security config.

**Already sound (kept):** backups disabled; only the launcher activity exported; media session
restricted; logcat redaction; release key required; metadata queries carry only title, year,
type and language; caches were already bounded.

| Item | Status | Change |
|---|---|---|
| Image proxies disclosed, can be turned off | COMPLETED (JVM-verified) | Settings → Integrations → Image proxies (on by default, as before): names wsrv.nl and i0.wp.com and what they see; off → no proxy request (test) |
| Automatic DNS over HTTPS disclosed, can be turned off | COMPLETED (JVM-verified) | Settings → Integrations → Secure DNS for blocked sites (on by default, as before): names Cloudflare and Google; off → no DoH lookup, blocked hosts stay unreachable (test) |
| Storage screen | NEEDS REAL-DEVICE TESTING | Settings → Advanced → Storage: sizes of Telegram videos, add-on streams, torrents, posters; limits; Clear per cache (Telegram after a confirmation, via TDLib `optimizeStorage`); refused while a video plays. UI and view model tested; sizes and clearing on a device untested |
| TMDB key encrypted | NEEDS REAL-DEVICE TESTING | AES-256-GCM with a Keystore key; plain keys from older versions migrated on first read; JVM tests use a stand-in cipher (Robolectric has no Keystore) |
| Updater checks SHA256SUMS.txt | COMPLETED (JVM-verified) | Mismatch, missing entry or missing file → deleted, not installed; parser and hash tested (FIPS test vector) |
| R8 for releases | NEEDS REAL-DEVICE TESTING | `app/proguard-rules.pro`; `-Pyourstream.minify=true` (off by default); release workflow `minify` checkbox; CI builds a minified release on every push. Minified APK builds; `dexdump` shows the classes the native libraries bind to (TDLib `JsonClient`, libtorrent SWIG callbacks, FFmpeg decoder) kept. Device checklist: `docs/RELEASE_R8.md` |
| Background work cannot crash the app | COMPLETED (compile-verified) | The Telegram player's progress saves and file releases use the shared logging playback scope (each player had its own scope without a handler); sync and torrent scopes got handlers. No new test |
| `docs/SECURITY_REVIEW.md` | COMPLETED | Assets, every outbound destination and what it receives, storage, platform surface, findings with status, what is unverified |
| Dependency vulnerability scanning | NOT STARTED | No scanner configured; suggested: Dependabot alerts in the repository settings |

**Files modified:** `core/network/.../SecureDns.kt`; `ui/core/.../Images.kt`;
`metadata/sync/.../TmdbKeys.kt`; `app/.../update/GitHubAppUpdates.kt`,
`app/.../account/AppStorage.kt` (new), `app/build.gradle.kts`, `app/proguard-rules.pro` (new);
`telegram/api/.../TelegramFileRepository.kt`, `telegram/core/.../TelegramClient.kt`,
`DefaultTelegramFileRepository.kt`, `telegram/tdlib/.../TdlibTelegramClient.kt`;
`playback/media3/.../PlaybackModule.kt`, `playback/torrent/.../TorrentEngine.kt`,
`sync/.../SyncModule.kt`; `ui/screens/.../settings/Storage.kt` (new), `AppPreferences.kt`,
`Settings.kt`, `SettingsCatalog.kt`, navigation; `strings.xml`; `.github/workflows/ci.yml`,
`release.yml`; `docs/SECURITY_REVIEW.md`, `docs/RELEASE_R8.md` (new), `docs/UI.md`.

**Tests added:** `SecureDnsTest` (fallback off) · `ImageRoutingTest` (proxies off) ·
`TmdbKeysTest` (encrypted at rest, migration, undecryptable key, removal) · `ChecksumTest` ·
`StorageTest` (sizes, Telegram confirmation, result and refusal).

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass (579 JVM tests
in the result files, 0 failures); lint 0 errors, 15 warnings (a new `UsableSpace` warning from
the storage page was fixed by using `getAllocatableBytes`). `:app:assembleRelease
-Pyourstream.minify=true`: builds; dex inspected as above.

**Real-device checks:** `docs/RELEASE_R8.md` (minified build); Settings → Storage sizes and each
Clear (with and without a video playing); TMDB key saved, app restarted, key still works; an
older version's plain key migrated; Image proxies / Secure DNS for blocked sites off on a network
that blocks TMDB (posters show titles, no requests to the proxies).
