# Implementation progress

Tracks the phased plan from [PRODUCT_AUDIT.md](PRODUCT_AUDIT.md). Statuses: NOT STARTED,
IN PROGRESS, COMPLETED, BLOCKED, NEEDS REAL-DEVICE TESTING. A phase is COMPLETED only when all
of its items are done and verified as stated; anything verified only on the JVM is marked so.

For a new conversation: read this file and `docs/PRODUCT_AUDIT.md` first; they are the state.

| Phase | Status |
|---|---|
| 0. Repository audit | COMPLETED |
| 1. Critical fixes | IN PROGRESS |
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

## Phase 1 — Critical fixes · IN PROGRESS

**Objective:** stop shipping regressions and close the release-blocking risks that need no
product redesign.

**Existing problems (from the audit):** no CI running tests or lint; releases could be signed
with a throwaway key; torrent streaming on by default without consent (IP exposure); release
logcat not redacted; intermittent Hilt/KSP "missing binding" build failures.

| Item | Status | Change |
|---|---|---|
| CI on every push / PR | COMPLETED (workflow written; first GitHub run pending) | `.github/workflows/ci.yml`: `test`, `:app:lintDebug`, `:app:assembleDebug`; reports uploaded on failure |
| Releases tested and signed with the release key | COMPLETED (takes effect on the next release) | `release.yml`: tests before building; fails without `RELEASE_KEYSTORE_BASE64`; every APK's certificate checked against the pinned SHA-256 (`35249306…07b2`, the key that signed v0.10.0, read from the published APK with `apksigner`) |
| Torrents off by default, with consent | COMPLETED | `AppSettings.torrentStreaming = false` (stored default too); `TorrentConsentDialog` before turning on, from Settings or from a torrent result; torrent rows stay tappable while off |
| Logcat redaction | COMPLETED | `AppLogger.forLogcat`: logcat gets redacted text; release builds log no stack traces (type + redacted message instead) |
| Intermittent Hilt/KSP build failure | IN PROGRESS | see below |

**Files modified:** `.github/workflows/ci.yml` (new), `.github/workflows/release.yml`,
`app/src/main/kotlin/dev/yourstream/log/AppLogger.kt`,
`ui/screens/.../settings/AppPreferences.kt`, `SettingsCatalog.kt`, `Settings.kt`,
`ui/screens/.../addon/CatalogTitle.kt`, `ui/screens/src/main/res/values/strings.xml`,
`docs/STREAMS.md`; tests: `app/src/test/.../log/LogcatRedactionTest.kt` (new),
`ui/screens/src/test/.../ScreensTest.kt` (`torrentStreamingIsOffByDefaultAndAsksBeforeTurningOn`).

**Hilt/KSP investigation so far:** every "missing" binding in the failed build came from one
Hilt module (`playback/media3/PlaybackModule.kt`), in a build that ran right after files in
that module changed. Reproduction attempts: an incremental build after a comment-only change and
after adding a new top-level class to the same module — both built correctly. A cold,
cache-less run of the CI command is the next attempt.

**Known limitations:** the effect of the consent dialog and logcat change on a device is not
verified; the release-workflow changes are verified only by reading (they run on the next
release).
