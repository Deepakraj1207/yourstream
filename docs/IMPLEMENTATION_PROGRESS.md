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
| 8. Local media | NEEDS REAL-DEVICE TESTING |
| 9. Advanced features | NEEDS REAL-DEVICE TESTING |
| 10. Release readiness | NEEDS REAL-DEVICE TESTING (v0.11.0 published as a pre-release) |

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

**CI:** push run 31 and pull-request run 32 passed on `537a93d`, including the new minified
release step (the push run now takes about 17 minutes instead of about 9).

**Real-device checks:** `docs/RELEASE_R8.md` (minified build); Settings → Storage sizes and each
Clear (with and without a video playing); TMDB key saved, app restarted, key still works; an
older version's plain key migrated; Image proxies / Secure DNS for blocked sites off on a network
that blocks TMDB (posters show titles, no requests to the proxies).


---

## Phase 8 — Local media · NEEDS REAL-DEVICE TESTING

**Objective:** videos stored on the device in the same library as Telegram videos, through a
source abstraction, without rebuilding the index. Details: `docs/LOCAL_MEDIA.md`.

**Inspected first:** `media` / `indexed_chat` entities and their keys, `RoomIndexStore.savePage`
(upsert by chat + message, duplicate detection), `MediaNormalizer` (the parser's input),
`IndexScheduler` / `IndexChatWorker` / `DefaultMediaIndexer` (queues, pause/resume),
`IndexerInitializer`, the library's version query and `MediaVersion`,
`LibraryMediaSourceResolver`, `PlayableMedia`, `TelegramMediaSourceFactory` (default factory for
other URIs), `TelegramPlaybackController` (every Telegram-only step), `AccountData` (sign-out).

**Decision (stated reason):** local media follows the account like the rest of the library.
Scanning only while signed in; sign-out removes local rows too. Otherwise the sign-out rule
("no account data while signed out") would wipe and rebuild local rows on every start. The
**choice** of folders is a device setting and survives, so sources are rescanned after sign-in.

| Item | Status | Change |
|---|---|---|
| Source abstraction + migration | COMPLETED | Schema v14 (AutoMigration): `indexed_chat.source` (TELEGRAM default), `media.locator`; reserved id range `LocalSourceIds`; migration test |
| Folder sources (SAF) | NEEDS REAL-DEVICE TESTING | Picker, persisted access, recursive listing (`AndroidLocalFileLister`), scan worker on the per-chat queue; scanner tested on real Room with a stand-in listing |
| All videos (MediaStore) | NEEDS REAL-DEVICE TESTING | `READ_MEDIA_VIDEO` / partial access on 14+ / `READ_EXTERNAL_STORAGE` ≤ 12, asked only on "Add all videos" |
| Library integration | COMPLETED (JVM-verified) | Titles, episodes, duplicates, availability unchanged; the same film on Telegram and on the device is one title with two versions (tested) |
| Playback | NEEDS REAL-DEVICE TESTING | Resolver checks the file is readable and plays `content://` directly; Telegram streaming steps skipped; a gone file falls through to another version (tested on the JVM) |
| UI | COMPLETED (JVM-verified) | Settings → Content → Files on this device (add folder, add all videos, remove, scan again, signed-out note); Library status wording no longer says "From Telegram" |
| Settings and first-run counts | COMPLETED | "N chats" counts Telegram chats only |

**Files modified:** `core/database` (entities, DAO, v14 schema, `DatabaseSafety`),
`media/api/.../LocalMedia.kt` (new), `media/parser/.../MediaNormalizer.kt`,
`media/indexer` (`local/*` new, `IndexScheduler`, `IndexerInitializer`, `EntityMappers`, DI),
`library` (`MediaVersion`, mapper), `playback/media3` (`PlayableMedia`, resolver,
`LocalFileCheck`, controller guards, DI), `ui/screens` (`settings/LocalFiles.kt` new, catalog,
navigation, counts, strings), `app` (manifest permissions, `DeviceAccountData` comment);
`docs/LOCAL_MEDIA.md` (new), `docs/SECURITY_REVIEW.md`, `docs/TELEGRAM_SETUP.md`.

**Tests added:** `LocalScannerTest` (7) · `LocalMediaLibraryTest` (3) ·
`LibraryMediaSourceResolverTest` (+2) · `LocalFilesTest` (2) · `MigrationTest.v13ToV14…`.
Positional `indexed_chat` fixtures in five test files got the new `source` value; the
end-to-end pipeline passes a check that no local file is readable (it only uses Telegram).

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass (594 JVM tests
in the result files, 0 failures); lint 0 errors, 15 warnings (a new `SelectedPhotoAccess`
warning was fixed by handling Android 14's "selected videos only").

**Known limitations:** no duration or resolution read from folder files (the name gives the
resolution; the player finds the duration); subtitles beside a local video are not picked up
yet; a playback error on a local file may be worded like a network error.

**CI:** push run 35 and pull-request run 36 passed on `6cadd9a` (including the minified
release build).

**Real-device checks:** `docs/LOCAL_MEDIA.md` "Needs a device" (8 steps).


---

## Phase 9 — Optional advanced features · NEEDS REAL-DEVICE TESTING

**Objective (audit items 20–24, optional):** pick what users feel most, at low risk; state what
is deferred and why.

**Inspected first:** the add-on stream player (no progress kept), the catalog title page (what
identifies a stream), the library's `CompletionPolicy`, the Telegram file repository (full
downloads, file updates), `LiveIndexUpdater` (download state into the index), `StreamCache`
(what it trims), the Details mapping and version rows, the sign-out wipe.

| Item | Status | Change |
|---|---|---|
| 20. Resume for add-on streams | NEEDS REAL-DEVICE TESTING | `StreamProgressStore` (table `stream_progress`, schema v15, AutoMigration), keyed by catalog title + episode (IMDb id when known: any stream of the episode resumes); library completion rules; "Resumed from … · Start over"; wiped on sign-out. Store tested on real Room; the player wiring is not covered by a test |
| 24 (part). Offline downloads | NEEDS REAL-DEVICE TESTING | Download / Cancel / Remove download on a title's versions (`VideoDownloads`, TDLib full download at low priority, live progress); kept downloads never trimmed by the stream cache; cleared on sign-out. Tested against the simulated Telegram file layer, not TDLib |
| Bug found: "On this device" never shown | COMPLETED | The Details mapping compared with `COMPLETED`; the index stores `COMPLETE` (Phase 5). Fixed; `MappingTest` now goes through the mapping |
| 21. Foreground-service indexing | NOT STARTED (deferred) | Android 14/15 limit the `dataSync` service type (time limits, start restrictions); needs device data on large channels first |
| 22. Unify network monitors / scopes / FTS tables | NOT STARTED (deferred) | Refactor with migration risk and no user-visible change; the crash-prone scopes were fixed in Phase 7 |
| 23. Split `ui:screens` by feature | NOT STARTED (deferred) | Build-structure refactor; the brief says not to rewrite for a preferred architecture |
| 24. SMB/WebDAV | NOT STARTED (deferred) | New protocols and stored credentials: a project of its own |
| 24. TV polish | NOT STARTED (deferred) | Cannot be judged without a TV |

**Files modified:** `core/database` (`StreamProgressEntity`, `StreamProgressDao`, v15),
`watch/engine/.../StreamProgress.kt` (new), `playback/media3/.../VideoDownloads.kt` (new),
`PlaybackModule.kt`, `playback/stream/.../StreamCache.kt`, `ui/screens` (`addon/StreamPlayer.kt`,
`addon/CatalogTitle.kt`, `navigation/Routes.kt`, `details/Details.kt`, `DetailsScreen.kt`,
`player/PlayerScreen.kt` resume chip shared, strings), `app/.../DeviceAccountData.kt`;
`docs/PLAYBACK.md` §9c–9d, `docs/SECURITY_REVIEW.md`.

**Tests added:** `StreamProgressTest` (4, real Room) · `VideoDownloadsTest` (3, simulated
Telegram files) · `MappingTest.versionsSayWhereTheyAreFromTheStoredDownloadState` ·
`ScreensTest.versionsCanBeDownloadedCancelledAndRemoved` · `MigrationTest.v14ToV15…` ·
`AccountDataTest` now asserts `stream_progress` is wiped.

**Tests run:** `./gradlew --continue :app:assembleDebug test :app:lintDebug`: pass (604 JVM tests
in the result files, 0 failures); lint 0 errors, 15 warnings (a new `UseKtx` warning from the
sign-out step was fixed).

**CI:** push run 39 passed on `5fb367b` (including the minified release build).

**Known limitations:** downloads only from a title's Details (a movie's versions); episodes are
not downloadable from the Episodes screen yet; downloads stop when Android ends the app.

**Real-device checks:** download a movie, turn on airplane mode, play it; cancel midway and
resume; remove the download and check Settings → Storage drops; play an add-on stream for a few
minutes, close, reopen the same episode from another add-on: it resumes; Start over.


---

## Phase 10 — Release readiness · NEEDS REAL-DEVICE TESTING

**Objective:** a full test pass, a safe upgrade from the last release, current documentation and a
signed release; check that it installs over the previous version.

| Item | Status | Detail |
|---|---|---|
| Full test pass | COMPLETED | `./gradlew --continue --rerun-tasks :app:assembleDebug test :app:lintDebug` (nothing reused): pass, 608 JVM tests, 0 failures; lint 0 errors, 15 warnings. CI push run 43 and pull-request run 44 passed on `31bb326`, including the minified release build |
| Database upgrade from v0.10.0 | COMPLETED (JVM-verified) | v0.10.0 shipped schema v12. New `MigrationTest.upgradeFromTheV0_10_0ReleaseKeepsTheUsersData`: a v12 database with a chat, a file, a title, progress and a favorite goes through 13 → 14 → 15 and is opened by the app's code with everything intact |
| Settings upgrade | COMPLETED (JVM-verified) | Bug found: 0.10 wrote `torrents = true` (then the default) whenever any setting was saved, so the Phase 1 consent rule would not have applied to updating users. A one-time settings upgrade turns torrents off; turning them on again goes through the consent dialog (`SettingsUpgradeTest`, 3 cases). Stated in the release notes |
| Documentation | COMPLETED | README rewritten for the current app (it still described "Stage 1 debug screens"); `docs/RELEASE.md` (checklist, pre-release, promotion); release notes "What's new in 0.11" |
| Signed release | COMPLETED | Release workflow run 15 on `31bb326`: tests, universal + 4 per-CPU APKs, release-key certificate check, Telegram credentials check, `SHA256SUMS.txt`. Published [v0.11.0](https://github.com/Deepakraj1207/yourstream/releases/tag/v0.11.0) **as a pre-release** (new workflow option): GitHub's "latest" is still v0.10.0, so the in-app updater does not offer 0.11 until it is promoted. R8 off |
| Installs over v0.10.0 | COMPLETED statically; NEEDS REAL-DEVICE TESTING | Downloaded both arm64 APKs: same package `dev.yourstream`; versionCode 15 > 14; identical signing certificate (SHA-256 `35249306…07b2`), both verify (v2); the download matches `SHA256SUMS.txt`. Not installed on a device (no device or emulator here) |

**To finish (owner, on a phone):** `docs/RELEASE.md` "After publishing a pre-release": install
0.11 over 0.10, run the key device checks, confirm the old library and progress survived, then
mark the release latest so the in-app updater offers it.

**Overall state after the ten phases:** every phase's code is in, tested on the JVM and in CI.
What remains is real-device verification (Phases 2–4 and 6–10, checklists in this file and in
`docs/TELEGRAM_SETUP.md`, `docs/PLAYBACK.md` §9b, `docs/LOCAL_MEDIA.md`, `docs/RELEASE_R8.md`)
and the deferred Phase 9 items.


---

## After v0.11.0: fixes from device feedback (13 Nov 2026)

First real-device report (screenshots from the owner's phone).

| Report | Cause | Fix |
|---|---|---|
| Many movies grouped as one title "Tel" | `@SPEEDY_LinkZ_Nowhere_2023_…_Tam_+_Tel.mkv`: the `@handle` rule allowed underscores, so it removed the whole underscore-joined name; only "+ Tel" was left | A run after "@" longer than a Telegram username (32) or holding a year/quality is a whole name: only its first segment and following brand words ("LinkZ", "Movies") are the handle (`Preprocessor.stripHandles`) |
| "Scam 2003 …" shown as "Link to this message" | Same: nothing left of the name, so the post above the file was used, whose text was "Link to this message" | Same fix; link phrases ("Link to this message", "Click here", "Join us") are never a title; `S02_EP_01_05` reads as episodes 1–5 |
| "Toonworld4all Naruto" | Site brand in the name | Words like `Toonworld4all`, `Movies4u`, `Filmy4wap` are brands, not titles |
| Movies / Series in the navigation | User's choice | Removed from `MAIN_TABS` (Home, Search, Library, Settings); still opened from Home and the Library |
| Navigation look | User's reference (manga store app); on 14 Nov 2026 the user picked option 5 "Panel Grid" from seven mockups | `MangaNavigationBar`: slanted inked comic panels with gutters, the current one filled with ink; classic style keeps Material 3 |

Parser version 5 → 6: libraries already indexed are re-parsed after the update and regrouped
(existing `ReparseWorker` + library sync by signature). Tests: `UnderscoreHandleNamesTest` (6,
the reported names plus guards: "World War Z", spaced handles, "E05 10 bit"), navigation tests
for four tabs and the manga bar (names, selection, 48 dp). Full gate: pass; lint 0 errors,
15 warnings. Needs a device check: open the library after updating and confirm the titles.

**Released:** [v0.11.1](https://github.com/Deepakraj1207/yourstream/releases/tag/v0.11.1) as a
pre-release (release run 16 on `c148c7a`): versionCode 16, same release certificate
(`35249306…07b2`), arm64 APK matches `SHA256SUMS.txt`; GitHub's "latest" is still v0.10.0.
**Promoted:** v0.11.1 marked latest on 13 Nov 2026 (release workflow, promote mode; nothing
rebuilt). The in-app updater now offers it.


## Device report 2 (14 Nov 2026): sign-in loading forever

**Cause (found in code):** the crash-loop guard counted every run that ended within 30 s as an
"early death" (swiping the app away, installing an update, Android reclaiming memory). Three in a
row started the app in safe mode, where Telegram is never started, and the sign-in screen showed
only a spinner, which made users close the app again, feeding the loop.

**Fixes:** early ends count only when Android reports a crash or ANR for the previous run
(`ApplicationExitInfo`, Android 11+; older versions: only the app's own crash reports); the
sign-in screen never spins forever: Telegram not running, failed, or still starting after 20 s
is said plainly with Start Telegram / Try again (`StartProblem`); the first run keeps its
Connect button while Telegram starts. Tests: `DiagnosticsTest` (rule changed, with the reason),
`ViewModelTest.signInNeverSpinsForeverWhenTelegramIsNotReady`, `FirstRunTest`. Needs a device
check: open the app after updating; sign-in opens (or explains why not).

Also: the Nuvio plugins tab was removed (a pasted plugin link still gets a one-line message);
example texts no longer say "Tamil" ("unwatched action movies", "comedy movies").

## v0.11.2 release and Panel Grid navigation (14 Nov 2026)

* **v0.11.2 published as the latest release** (the user said "Publish"). Checked on the published
  files: `releases/latest` is v0.11.2 and not a pre-release; the arm64 APK's SHA-256 matches
  `SHA256SUMS.txt`; `aapt2` shows versionCode 19, versionName 0.11.2; `apksigner` shows the pinned
  release certificate (`35249306…07b2`). Status: NEEDS REAL-DEVICE TESTING (the sign-in fix in
  particular).
* **Manga navigation: Panel Grid.** Reason: the user picked option 5 from a page of seven mockups.
  `MangaNavigationBar` now draws each tab as a slanted, inked comic panel with gutters; the current
  panel is filled with ink (same rule in dark and light, from the theme's `onSurface`/`surface`).
  The tab semantics (role, selected state, names for screen readers with tab names off) are
  unchanged. New test: `theMangaPanelGridKeepsTouchTargetsOnTheNarrowestPhones` (320 dp, light).
  Status: NEEDS REAL-DEVICE TESTING (look on real screens, press feedback, TalkBack).

## Add-on refresh and streams grouped by add-on (14 Nov 2026)

User request: "Add refresh button on addon like nuvio" and "Display streams as addon by group".
Nuvio's source could not be read from this session (GitHub access is limited to this repository),
so both follow Nuvio's behaviour as it is commonly known, not its code.

* **Add-ons page refresh.** Reason: add-ons change their manifests (new catalogs, names, stream
  support) and the page only checked add-ons that had not answered yet. A top-bar refresh button
  (and pull-to-refresh) fetches every manifest again (`CatalogAddons.refresh`), keeps a renamed
  add-on's new name across restarts, rebuilds the catalog manager, and reports how many answered.
  Tests: `CatalogAddonsRefreshTest` (fake add-ons, no network: proves the bookkeeping, not real
  add-ons) and `AddonsAndStreamsTest.refreshButtonAsksAgainAndSaysHowManyAnswered`.
* **Streams grouped by add-on.** Reason: the list waited for the slowest add-on and mixed groups
  with no way to look at one add-on. Now each add-on's streams appear as it answers, under its
  name and count, with All / per-add-on chips, a refresh button, a stable order, and the add-ons
  that found nothing or did not answer named. Also fixes a race: selecting another episode while a
  slow add-on was still answering could show the previous episode's streams (the older request is
  now cancelled). Tests: `AddonsAndStreamsTest` (grouping, chips, progressive answers, order).
* Status: NEEDS REAL-DEVICE TESTING (real add-ons, slow networks, TV remote focus on the chips).

## Movies in raw pieces streamed as one file (15 Nov 2026)

User report: 4K movies in their channel are split into raw pieces (".mkv.001", ".002", …).
Before, each piece was a separate version; only the first had the container header, so at best
the start played with a wrong duration, and later pieces failed.

* Reason: Telegram's upload limit makes this the usual way 4K movies are posted. The pieces are
  joined at the byte level while streaming (no download or copy first), so everything that works
  for a normal file (duration, seeking, tracks, resume, offline download) works for these.
* Changes: `SplitFiles` + `LibraryDao.splitParts` (one version with every piece), resolver
  (`resolveSplit`, missing pieces named), `TelegramMediaUri` split form, `TelegramMediaDataSource`
  (reads across pieces, prefetches the next piece), controller (focus/stats/release per piece),
  `VideoDownloads` (all pieces), title page ("N pieces"). Details in `docs/STREAMS.md`.
* Tested: `SplitFilesTest`, `SplitPlaybackTest` (real ExoPlayer on the simulated Telegram with
  committed clips cut into pieces), resolver and download tests. The simulated Telegram is not
  TDLib, and the clips are small: real multi-GB 4K pieces have not been played.
* Status: NEEDS REAL-DEVICE TESTING (a real channel's 4K pieces: start time, seeking far into a
  late piece, the boundary between pieces, 4K decoding on the device).

## v0.11.3 release (15 Nov 2026)

Published as the latest release (not a pre-release), on the user's instruction ("release as
latest"), with the Panel Grid navigation, add-on refresh, streams grouped by add-on, and movies
in raw pieces. Checked on the published files: `releases/latest` is v0.11.3; the arm64 APK's
SHA-256 matches `SHA256SUMS.txt`; `aapt2` shows versionCode 20, versionName 0.11.3; `apksigner`
shows the pinned release certificate (`35249306…07b2`). The in-app updater offers it.
Status: NEEDS REAL-DEVICE TESTING (above all, real 4K movies in pieces from a channel).

## Device report 3 (15 Nov 2026): Layout, add-ons, startup, opening logo

Requests: Layout page like Nuvio's with its options working; add-ons page like Nuvio's; Cinemeta
as the only default add-on; the full-screen sign-in shown briefly at launch; an opening logo; a
player like Nuvio's (separate entry). Nuvio's current source (NuvioMedia/NuvioMobile, read through
public pages) was used as the reference for labels and options; no code was copied.

* **Only Cinemeta built in.** Reason: the user adds add-ons themselves. `catalog.providers` now
  defaults to `none`. Users who relied on the Indian catalogs add them by link.
* **Startup flash fixed.** Reason: Home showed a full-screen "Connect Telegram" while the add-on
  rows loaded, then the sign-in panel above them. Now the panel and placeholder rows show while
  loading. Test: `signInPanelDoesNotJumpFromFullScreenWhileAddonRowsLoad`.
* **Opening logo.** Android's launch screen (core-splashscreen) with the app's bubble on ink, then
  `LaunchIntro` (about 1 s; tap skips; still with animations off). Tests: `LaunchIntroTest`. The
  test renderer cannot draw the logo vector mid-animation: the look is checked on a device only.
* **Add-ons page like Nuvio's.** Overview, install box, cards with logo, version, switch, move
  up / down (the order drives Home's rows and stream groups), refresh, settings page, delete with
  confirmation, chips, description and summary line. Tests: `AddonsAndStreamsTest`,
  `CatalogAddonsRefreshTest.movingAnAddonChangesTheOrderEverywhereAndIsKept`, manifest test.
* **Layout like Nuvio's** (docs/UI.md). Tests: `LayoutSettingsTest` (7), screenshots.
* Status: NEEDS REAL-DEVICE TESTING (all of the above; the opening logo's look and timing in
  particular).

* **Player like Nuvio's** (docs/UI.md "Player controls"). Reason: the user asked for Nuvio's
  functionality, control panel and options. Lock, labelled pills, time pills, Sources (other files
  of the title, switched at the current position), sideways swipe-to-seek, subtitle style menu
  (size, colour, position, bold, background), edge zones for system gestures; both players.
  Tests: `PlayerScreenTest.nuvioControlsLockPillsSourcesAndSubtitleStyle`,
  `swipeToSeekCoversMoreOfLongerVideos`; existing player tests unchanged and passing. Not built:
  chapters, audio/subtitle delay, amplification, libmpv, watch party (listed in docs/UI.md).
  Status: NEEDS REAL-DEVICE TESTING (gestures, lock, Sources switching on a real title).

## v0.11.4 release (15 Nov 2026)

Published as the latest release on the user's instruction, with the Nuvio-style player, Layout
and Add-ons pages, Cinemeta as the only built-in add-on, the opening logo and the startup fix.
Checked on the published files: `releases/latest` is v0.11.4 and not a pre-release; the arm64
APK's SHA-256 matches `SHA256SUMS.txt`; `aapt2` shows versionCode 21, versionName 0.11.4;
`apksigner` shows the pinned release certificate (`35249306…07b2`). The in-app updater offers it.
Status: NEEDS REAL-DEVICE TESTING.

## Device report 4 (15 Nov 2026)

* **Rows jumping while scrolling** (the user's recording): fixed with one poster text height.
  Test: `RowHeightTest`.
* **Posters matched automatically; Check matches removed.** Reason: the user wants matching to be
  the app's job. One 65% threshold (else the file name's title stays), duplicates of the same film
  are not doubt, titles left waiting are decided again; the queue screen, Home banner, Settings row
  and library status step are gone; "Fix match" stays. Tests: `MatchingTest`, `LibraryMetadataServiceTest`.
* **Nuvio's navigation bar in the classic theme**, with Nuvio's Adaptive / Always expanded /
  Always compact / Classic options (plus Panel Grid for the manga look) and Glass glow. Tests:
  `LayoutSettingsTest.adaptiveBarShrinksWhileScrollingDownAndGrowsBack`, floating bar test.
  Not copied: Nuvio's profile picture on the last tab (this app's last tab is Settings).
* **Hero carousel from hero catalogs** (Nuvio's). Tests: `HeroCarouselTest`,
  `homeShowsTheHeroCarouselWhenHeroCatalogsAreChosen`. The hero starts below the status bar (the
  shell keeps that inset for every tab); the rating badge shows "★" (the add-on's rating may not be
  IMDb's).
* Status: NEEDS REAL-DEVICE TESTING.

## v0.11.5 release (15 Nov 2026)

Published as the latest release on the user's instruction (Nuvio navigation bar, hero carousel,
automatic poster matching without Check matches, row-height fix). Checked on the published files:
`releases/latest` is v0.11.5 and not a pre-release; the arm64 APK's SHA-256 matches
`SHA256SUMS.txt`; `aapt2` shows versionCode 22, versionName 0.11.5; `apksigner` shows the pinned
release certificate (`35249306…07b2`). Status: NEEDS REAL-DEVICE TESTING.

## Device report 5 (15 Nov 2026)

Implemented after the user approved the mockups:
* **Hero stopped halfway:** the auto-advance was keyed on `currentPage`, which changes in the
  middle of a move and restarted the effect, cancelling the animation. It is now keyed on
  `settledPage`.
* **One poster per title; Telegram files as a stream source:** see STREAMS.md. Tests:
  `StreamSourcesTest`, `LibraryMetadataServiceTest.catalogEpisodeFindsTheLibraryEpisodeIncludingDoubleEpisodes`.
* **"From Telegram" tag on Recently added; Series row removed.** Test: `StreamSourcesTest.recentlyAddedIsTaggedFromTelegram`.
* **Sort & filter for streams** (this app's design; Nuvio has none). Tests: `StreamSourcesTest`.
* **Search like Nuvio** (field, recent searches, Discover, rows per add-on, Nuvio's messages): see
  UI.md. Tests: `ScreensTest`, `ViewModelTest`, `CatalogManagerTest`.
* **Adaptive bar animated like Nuvio:** see UI.md. Tests: `LayoutSettingsTest`.
* **Gate:** 680 tests, 0 failures. Lint has 0 errors and 15 warnings. The 4 warnings above the
  previous 11 are "newer version available" notices (AGP 9.5.1, kotlinx-serialization 1.13.0)
  that appeared because those versions were published upstream, not from these changes.
  Dependencies were left as they are in this change.
* Status: NEEDS REAL-DEVICE TESTING.

## v0.12.0 release (15 Nov 2026)

Published as the latest release on the user's instruction:
- one poster per title, with Telegram files as a stream source;
- stream Sort & filter;
- Search like Nuvio;
- the adaptive bar animated like Nuvio;
- Home: "From Telegram" tag and no Series row;
- the hero carousel fix.

A first run was cancelled before it published, because the release notes still described 0.11.5.
The notes were updated and run 24 published the release.

Checked on the published files:
- `releases/latest` is v0.12.0 and is not a pre-release.
- The arm64 APK's SHA-256 (`24a95782…7481`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 24 and versionName 0.12.0.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).

Status: NEEDS REAL-DEVICE TESTING.

## Device report 6 (15 Nov 2026): app closed while streams loaded (0.12.0)

* **Cause:** the new stream sort (`StreamFilter.apply`). The "best quality" tie-break used
  `sizeBytes ?: 0` and "Largest" used `?: -1`. For streams without a size, those are `Int` values,
  compared with other streams' `Long` sizes, which threw `ClassCastException` while composing
  the list. Add-ons usually send a mix of streams with and without sizes. The 0.12.0 tests gave
  every stream a size, or none, so they never compared the two.
* **Fix:** `0L` / `-1L`. `qualityOf` now returns null instead of throwing for an unknown height.
* **Tests:**
  - `StreamSourcesTest.streamsWithAndWithoutSizesSortTogether`, checked to fail with the old
    code (same exception);
  - `TitleStreamsCrashTest`, which runs the real title-page view model with a Torrentio-like
    add-on (40 results, some without a size) and a Telegram file, and renders the page. It
    reproduced the device crash before the fix.
* Gate: 682 tests, 0 failures; lint 0 errors, 15 warnings (unchanged).
* Status: NEEDS REAL-DEVICE TESTING.

## Device report 7 (15 Nov 2026): title page like Nuvio's, from TMDB

The user had added a TMDB key, but the title page did not use it. It is now a Nuvio-style detail
page from TMDB (see UI.md), with Play opening the streams page. Only IMDb and TMDB scores are
shown: the other scores in the user's screenshot come from MDBList, which needs its own key.
Gate: 687 tests, 0 failures; lint 0 errors, 15 warnings (unchanged). Status: NEEDS REAL-DEVICE
TESTING.


## v0.12.1 release (15 Nov 2026)

Published as the latest release on the user's instruction. It contains:
- the fix for the crash while streams loaded (device report 6);
- the title page like Nuvio's, from TMDB, with Play opening the streams (device report 7).

Checked on the published files (run 25):
- `releases/latest` is v0.12.1 and is not a pre-release.
- The arm64 APK's SHA-256 (`db372d27…e5aa`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 25 and versionName 0.12.1.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).

Status: NEEDS REAL-DEVICE TESTING.

## Device report 8 (15 Nov 2026): startup hero swap, logo boxes, TMDB score

From the user's screen recording and screenshots of 0.12.1:
* **The old home showed first, then the new one.** Until the hero catalogs answered, Home showed
  the library hero (Continue watching), then swapped it for the carousel. Home now keeps the
  carousel's space empty while it loads (`HomeUiState.heroLoading`, `HeroCarouselPlaceholder`).
  Test: `LayoutSettingsTest.homeShowsTheHeroCarouselWhenHeroCatalogsAreChosen`.
* **The title logo had a box behind it, and production logos looked wrong.** `Artwork` always
  drew its grey placeholder under the image. Transparent PNGs (logos) showed it as a box, and
  dark company logos disappeared into it. `Artwork(placeholder = false)` is now used for title
  and company logos.
* **The carousel showed the title twice** (printed on the poster, and as text). With a TMDB key,
  slides use TMDB's logo over a poster without printed title (or the backdrop). The wait for
  TMDB is at most 3 s; slides it does not answer for keep the add-on's artwork. The logo carries
  the title for screen readers.
* **The TMDB score showed "67".** It is now "6.7", out of 10 like IMDb.
* Gate: 687 tests, 0 failures; lint 0 errors, 15 warnings (unchanged). Status: NEEDS REAL-DEVICE
  TESTING.

## v0.12.2 release (15 Nov 2026)

Published as the latest release on the user's instruction (device report 8: no startup hero
swap, TMDB logos in the carousel, no box behind logos, TMDB score out of 10).

Checked on the published files (run 26):
- `releases/latest` is v0.12.2 and is not a pre-release.
- The arm64 APK's SHA-256 (`1f4f7c88…c27a`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 26 and versionName 0.12.2.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).

Status: NEEDS REAL-DEVICE TESTING.

## Device report 9 (15 Nov 2026): slow hero carousel, reload on return, manga carousel

* **The carousel reloaded every time the user came back from another tab.** Home's state stopped
  listening 5 s after Home left the screen (`WhileSubscribed`). The cold hero and catalog-row
  flows then started over: catalog pages, library check, TMDB art, with the placeholder in
  between. They now live in `HomeSources`, an app-wide singleton that computes once and follows
  add-on, hero-choice and layout changes. Coming back to Home shows them at once.
* **Slow at every start.** The last carousel is saved (`home_hero_cache`, used only for the same
  hero choice and add-ons) and shown at once. It is refreshed quietly; when nothing changed,
  nothing on screen changes. Test: `HeroCarouselTest.savedSlidesComeBackTheSame`.
* **Manga style for the carousel.** Each slide is an inked comic panel with speed lines, a red
  FEATURED caption, the title (or logo) in the manga lettering, ink boxes for the rating and
  language, a square VIEW DETAILS button, and ink-square dots. This is the same look as Home's
  manga hero. Test: `HeroCarouselTest.mangaLookDrawsInkedPanels`; screenshot `phone-manga-hero`.
* Gate: 690 tests, 0 failures; lint 0 errors, 15 warnings (unchanged). Status: NEEDS REAL-DEVICE
  TESTING.

## Player alignment (15 Nov 2026)

The user asked for a better player with better alignment. Checked on rendered phone (landscape
and portrait) and TV screenshots:
* **One edge for everything.** Before, the back arrow, title, resume note, seek bar, times and
  buttons each started at a different inset. Now the title, resume note, times, timeline and
  buttons share one gutter (20 dp phones, 28 dp tablets, 48 dp TV). Icon buttons sit 12 dp
  further out, so their icons line up with it. Landscape keeps clear of the camera cutout.
* **Centre transport level:** the −10/+10 labels hang below their circles without taking space,
  so rewind, play and forward share one centre line.
* **Times over the ends of the timeline**, not a row below it with a gap.
* **Bottom row:** previous and next are round pills like the labelled ones. All pills are drawn
  44 dp tall (48 dp touch area) and centred. On phones they wrap onto a second centred line when
  they do not fit (portrait); on TV they stay one scrolling row.
* **Quality badge:** now a pill as tall as the icons beside it.
* **Panels:** the title, options, close icon and subtitle-style controls share one 24 dp edge.
* **Regression caught by `PlayerScreenTest.tvTransportPanelsAndBack` and fixed:** the TV row
  first used `BoxWithConstraints`, which composes later, so Play was not focused when the player
  opened.
* New screenshot `phone-player-portrait`. Gate: 691 tests, 0 failures; lint 0 errors, 15
  warnings (unchanged). Status: NEEDS REAL-DEVICE TESTING.

## v0.12.3 release (15 Nov 2026)

Published as the latest release on the user's instruction. It contains:
- device report 9: the carousel and rows are kept across tabs and restarts, and the carousel has
  a manga style;
- the player alignment pass.

Checked on the published files (run 27):
- `releases/latest` is v0.12.3 and is not a pre-release.
- The arm64 APK's SHA-256 (`2700c4b2…2395`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 27 and versionName 0.12.3.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).

Status: NEEDS REAL-DEVICE TESTING.

## Haptic feedback (16 Nov 2026)

The user asked for haptic feedback in the app. It is one shared helper (`Haptic`, `rememberHaptics`)
used for tabs, chips, switches, the title page, Sort & filter, the carousel, Search and the
player (taps, double-tap and swipe seek, scrubbing, brightness and volume steps, hold-for-speed,
lock). See UI.md for the full map. It follows the phone's touch-feedback setting, and Settings →
Haptic feedback turns it off. Tests: `HapticsTest`. Gate: 693 tests, 0 failures; lint 0 errors,
15 warnings (unchanged). Status: NEEDS REAL-DEVICE TESTING (vibration cannot be felt in tests).

## Device report 10 (16 Nov 2026): slow, glitchy title page; cut-off add-on chips

From the user's two screen recordings:
* **The title page glitched.** It drew the add-on's details first (plain title, genres,
  "Movie Details", cast initials), then TMDB's answer replaced them: the picture blanked to grey
  before the backdrop came in, and new rows pushed the page down.
  * While TMDB answers (`detailsLoading`, at most `DETAILS_WAIT_MS` = 4 s), the page keeps
    fixed space: an empty logo and genres area and placeholder bars. Then it fills in once.
  * The list's poster shows at once, and TMDB's backdrop is drawn over it only when it has
    loaded (`Artwork(placeholder = false)`), so the picture never blanks.
  * Without a TMDB key the page never waits. A TMDB answer after 4 s still fills the page.
* **The poster loaded slowly.**
  * TMDB remembers each IMDb → TMDB id, so a title seen before (in the carousel, or on a
    revisit) needs one request instead of two.
  * The page's backdrop is w780 instead of w1280.
* **The add-on chips were cut off** ("8" for "All · 8"): they scrolled in a strip between
  Sort & filter and refresh. Now Sort & filter and refresh sit on their own row at the page's
  edges, and the chips scroll edge to edge below with the first on the page's edge.
* Tests: `TitleDetailsPageTest.whileTmdbAnswersThePageKeepsItsSpace`, `TitleStreamsCrashTest`
  (loading cleared; never set without a key), `TmdbProviderTest` (id remembered, w780). Gate:
  694 tests, 0 failures; lint 0 errors, 15 warnings (unchanged). Status: NEEDS REAL-DEVICE
  TESTING.

## Device report 11 (21 Nov 2026): player edge strip and spinner; fast indexing of huge channels

* **Player, from the user's screenshot** (landscape, camera cutout on the left):
  * The whole control layer, its shading included, was pushed in by the cutout inset, so a
    bright unshaded strip stayed beside the cutout. Now the shading reaches the screen edges,
    and only the controls keep clear of the cutout, by the larger inset on both sides so they
    stay centred on the screen.
  * A loading spinner showed behind Play. The centre swapped between the spinner and Play, and
    a second spinner drawn under the controls showed while they faded. Now Play always stays,
    and buffering is one ring drawn around it, on top (alone, on a dark disc, when the controls
    are hidden).
  * Tests: `PlayerScreenTest.bufferingRingsPlayInsteadOfHidingBehindIt`, screenshot
    `phone-player-buffering`. The cutout itself cannot be produced in Robolectric.
* **Fast indexing for very large channels.** See INDEXING.md "Very large channels": split backfill
  into 4 parallel id ranges, each resumable, with TDLib's id grid respected; one adaptive request
  gate (6 at once, halved on FLOOD_WAIT); titles from the post above deferred to the TITLES step
  for split scans. `LargeChannelIndexingTest`:
  * a split scan indexes every file once, across many budget stops;
  * 12,000 files at 300 ms per request: 39.5 s one page after another, 10.2 s split, with one
    extra request;
  * FLOOD_WAIT backs off and the scan finishes;
  * the gate halves and recovers;
  * small chats are not split.
* Status: NEEDS REAL-DEVICE TESTING (real Telegram timings and flood limits).

## v0.12.4 release (21 Nov 2026)

Published as the latest release on the user's instruction. It contains:
- haptic feedback;
- device report 10: the title page loading and the add-on chips;
- device report 11: the player edge strip and buffering ring, and fast indexing of huge
  channels.

Checked on the published files (run 28):
- `releases/latest` is v0.12.4 and is not a pre-release.
- The arm64 APK's SHA-256 (`9317b4ed…e9e6`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 28 and versionName 0.12.4.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).

Status: NEEDS REAL-DEVICE TESTING.

## Old releases removed (2026-11-22)

User request: keep the latest release as the first and only one. A one-off `workflow_dispatch`
job (run 44975383534, success) deleted the 22 releases v0.2.0 to v0.12.3 and their tags. The job
was removed again afterwards.

* Checked after the run: the only tag on GitHub is `v0.12.4`. `releases/latest` is v0.12.4, not a
  pre-release, with all six assets. The arm64 APK's digest is still `9317b4ed…e9e6`, the value
  recorded when it was released.
* Installed apps keep updating: the updater reads only `releases/latest`.
* Status: COMPLETED

## Windows app, stage 1 (22 Nov 2026)

User request: "Make windows version of this app". Stage 1, as proposed earlier: the Telegram
library and playback; details in `docs/WINDOWS.md`.

* New `:desktop` module (Compose Multiplatform 1.12.2, JVM). The Android app and its modules are
  unchanged.
  * It reuses the plain-Kotlin modules as they are, and compiles the TDLib client and the indexing
    engine from their Android modules' source files.
  * TDLib comes from tdl-coroutines' JVM build, which includes the Windows DLLs.
* Replaced for the desktop:
  * Room → `DesktopIndexStore`, a JSON-lines journal: crash-safe appends, cut-off lines skipped,
    compaction.
  * WorkManager → `IndexRunner`.
  * Media3 → VLC through vlcj, fed by `StreamServer` (HTTP byte ranges on 127.0.0.1 with a random
    path).
  * Screens → sign-in (phone, code, password, QR), Library, title page (versions; seasons and
    episodes), Chats, Settings, Player (tracks, resume, next episode, fullscreen, keys).
* Installer: MSI, per-user, with a fixed upgrade code. It ships VLC's 64-bit libraries, which CI
  downloads from VideoLAN and checks against VideoLAN's SHA-256. The app icon is rendered from
  the Android launcher icon.
* CI: a new `windows` job on windows-latest runs the desktop tests (with the bundled VLC) and
  builds the MSI as a downloadable artifact.
* Tests (18 in `:desktop:test`, all passing here on Linux with VLC 3 installed; none skipped):
  * TDLib native start up to the phone-number step;
  * the index store (restart, cut-off line, removal, compaction);
  * the shared engine indexing a fake chat into titles;
  * the stream server over real HTTP against the simulated TDLib download;
  * real VLC playing an MKV through the server, and seeking with a new byte range;
  * screens rendered with sample data, with clicks.
* Checked by running it here (Linux, Xvfb): the app starts, real TDLib 1.8.69 reaches "wait for
  phone number", and the sign-in screen renders.
* Checked on Windows (CI run 132, windows-latest):
  * the VLC download (vlc-3.0.24-win64.zip) matched VideoLAN's SHA-256, and 143 MB went into the
    installer;
  * the desktop tests ran with these counts: DesktopIndexStoreTest 4/4, LibraryTest 2/2,
    ScreensTest 5/5, StreamServerTest 4/4, TdlibNativeTest 1/1 (TDLib's Windows DLLs), and
    VlcPlaybackTest 1 passed with the bundled VLC (the seek test was skipped: no ffmpeg on the
    runner; it passes on Linux);
  * `packageMsi` built `YourStream-0.12.4.msi` (294 MB), kept as a CI artifact.
* Size: most of the installer is VLC (143 MB) and the TDLib jar, which also carries TDLib for
  Linux and macOS (unused on Windows). Dropping those from the Windows package is a possible
  follow-up.
* Not in stage 1: split files (.001…) as one movie; files on the PC; downloads; add-ons and
  Discover; sync with the phone.
* Status: NEEDS REAL-DEVICE TESTING on a Windows PC: real sign-in, indexing real chats, the video
  window (VLC in the app window, fullscreen, focus), large real files, and install, upgrade and
  uninstall.

## v0.13.0 release (22 Nov 2026)

Published as the latest release on the user's instruction ("Release it with the Windows
installer and also add universal apk to releases"). Release run 30, from `main` at the merge of
pull request #8.
- The APKs as before, with the universal APK now named `YourStream-v0.13.0-universal.apk` (the
  updater's choice is unchanged, see VersionTest).
- New: `YourStream-v0.13.0-windows-x64.msi` (294 MB, VLC 3.0.24 inside) and
  `SHA256SUMS-windows.txt`. The Windows job ran the desktop tests on Windows and checked that the
  Telegram credentials are built in.
- A first attempt (run 29) was cancelled before publishing: the desktop tests hung on the Linux
  runner. vlcj's discovery walked whole system library folders when VLC was missing; outside
  Windows the app now asks the system loader instead. Each desktop test also has a time limit now.

Checked on the published files:
- `releases/latest` is v0.13.0 and is not a pre-release. It has 9 assets: the universal APK, 4
  per-CPU APKs, the MSI and 2 checksum files.
- The arm64 APK's SHA-256 (`d82002dc…2519`) matches `SHA256SUMS.txt`.
- `aapt2` shows versionCode 30 and versionName 0.13.0.
- `apksigner` shows the pinned release certificate (`35249306…07b2`).
- The MSI's SHA-256 in `SHA256SUMS-windows.txt` (`b4cb1eb7…4a88`) equals GitHub's digest of the
  uploaded file.

Status: NEEDS REAL-DEVICE TESTING (Android as before). For Windows: install, sign in, play,
upgrade and uninstall on a real PC (docs/WINDOWS.md).

## Smaller Windows installer (22 Nov 2026)

User request: "Reduce the installer size". Done three ways:
- TDLib keeps only the build machine's platform (111 MB jar → about 20 MB on Windows x64).
- Only the icons the app uses are kept (36 MB → 0.05 MB).
- VLC plugins a player inside the app does not use are left out (143 MB → 98 MB).

Checked in CI run 144 (Windows):
- The MSI is 144,135,284 bytes, down from 294,338,039: 51 % smaller.
- Desktop tests pass on the slimmed jars and the trimmed VLC: SlimJarsTest 3/3, TdlibNativeTest
  1/1, and VlcPlaybackTest's playback test passed with the trimmed copy (the seek test is skipped
  on Windows: no ffmpeg). The rest pass as before.
- The Linux job is green too.

Status: COMPLETED (in CI). It reaches users with the next release.
