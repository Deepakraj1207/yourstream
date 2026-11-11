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
| 3. Media indexing | NOT STARTED |
| 4. Streaming engine | NOT STARTED |
| 5. Metadata and library | NOT STARTED |
| 6. UI/UX | NOT STARTED |
| 7. Performance and security | NOT STARTED |
| 8. Local media | NOT STARTED |
| 9. Advanced features | NOT STARTED |
| 10. Release readiness | NOT STARTED |

Decisions taken (user said "proceed" on the audit's recommendations, 11 Nov 2026):
add-ons and torrents stay as optional extras, torrents off by default; the dock question (glass
vs. plain Material 3) is decided in Phase 6.

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
0 errors, 14 warnings (unchanged).

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
