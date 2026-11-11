# Implementation progress

Tracks the phased plan from [PRODUCT_AUDIT.md](PRODUCT_AUDIT.md). Statuses: NOT STARTED,
IN PROGRESS, COMPLETED, BLOCKED, NEEDS REAL-DEVICE TESTING. A phase is COMPLETED only when all
of its items are done and verified as stated; anything verified only on the JVM is marked so.

For a new conversation: read this file and `docs/PRODUCT_AUDIT.md` first; they are the state.

| Phase | Status |
|---|---|
| 0. Repository audit | COMPLETED |
| 1. Critical fixes | COMPLETED (one item moved to monitored risks, see below) |
| 2. Telegram authentication | NOT STARTED |
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

**Next:** Phase 2 — Telegram authentication (clean logout and local data cleanup, account
switching, session-expired handling, plain-language errors, TDLib database encryption).
