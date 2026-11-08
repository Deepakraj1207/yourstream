# Robustness and diagnostics (Phase 13)

Goal: the app keeps working, and explains itself, when the real world misbehaves.

## What happens when…

| Situation | Behaviour | Where |
|---|---|---|
| **Telegram is unavailable** (not configured, TDLib fails, server unreachable) | The library, search, assistant and watch lists are Room-only and keep working. Startup records the failure and continues. Playback reports the file as unavailable; offline it falls back to the file id stored at indexing time | `YourStreamApp.startup`, `LibraryMediaSourceResolver` |
| **Metadata/catalog provider unavailable** | Per-provider circuit breaker (Phase 5/6) with fallback to the next provider and to cached (even expired) data. Diagnostics shows each provider's state and last error | `ServiceHealth`, `MetadataProvidersCheck`, `CatalogProvidersCheck` |
| **Provider changes its response format** | Bad JSON, wrong types, HTML error pages, empty or truncated bodies become `AppError.Unknown`. Anything a provider *throws* (mapping bugs) is caught by `guarded` at the call site and feeds the circuit breaker; fallback continues | `getJson`, `guarded`, tests in `TmdbProviderTest` / `ProviderManagerTest` |
| **Internet disappears** | TDLib reconnects by itself. Playback retries with backoff while the network is up and waits while it is down (resumes on reconnect). Background work waits for connectivity (WorkManager constraints). Telegram connection changes go to the event journal | `PlaybackRecoveryPolicy`, `monitorTelegram` |
| **A media file disappears** | At open, versions are tried best-first: a deleted message or undownloadable file falls through to the next version. During playback, a file error switches to another version at the same position (up to 2 switches), else a clear error | `LibraryMediaSourceResolver.firstPlayable`, `TelegramPlaybackController.recover` |
| **Database migration occurs** | Files are backed up before any upgrade, and the database is opened eagerly so problems surface at startup. If opening fails, user data is salvaged (below). Destructive migration is never used | `DatabaseOpener` |
| **Database is corrupted** | Unreadable files are moved to quarantine *before* Android's default handler would delete them, including corruption found later at runtime (wrapped open helper). The app gets a fresh database | `DatabaseOpener`, `QuarantiningOpenHelperFactory` |
| **Corrupted metadata** | Decoding of stored JSON is already tolerant. Integrity repair deletes unreadable cache rows (refetched), resets corrupted cast lists, and marks that metadata for refresh | `DatabaseIntegrity.repair` |
| **Android kills the process** | Indexing work is in WorkManager and re-runs; `RUNNING` chats are reset at start; cursors persist. Watch progress is saved every 10 s. The player saves its session every 5 s, and the next start offers **Resume** at that position | `IndexerInitializer`, `PlaybackSessionStore`, `InterruptedPlaybackBanner` |
| **An unexpected exception** | Uncaught exceptions are written to a crash report (stack, device, recent log) before the process dies. Exceptions in background coroutines are logged, not fatal. Repeated early deaths switch to **safe mode** | `AppDiagnostics`, `ApplicationScope` handler, `StartupGuard` |
| **Memory pressure** | `onTrimMemory`/`onLowMemory` release registered caches: search rankings, the search vocabulary, the in-memory log. Low-RAM devices keep a smaller log buffer | `MemoryPressure` |

## Database safety

`DatabaseOpener.open` replaces `YourStreamDatabase.create`:

1. **Read the on-disk version** without risk. The file header is checked first, and SQLite
   gets a no-op error handler: the default handler *deletes* a file it considers corrupt, even
   when only asked for the version.
2. **Unreadable file** → move it to `databases/quarantine/`.
3. **Upgrade pending** → copy the files to `databases/backups/` (the two newest are kept).
4. **Open now** (`writableDatabase`), so migrations run at startup in a background coroutine,
   not lazily on the first UI query.
5. **Open failed** (migration error, downgrade from a newer build, corruption):
   1. quarantine the files;
   2. create the current schema;
   3. run `DatabaseSalvage`:
      * for every table both schemas have, copy the columns both have;
      * rows that no longer fit are dropped one by one, not table by table;
      * rows with broken references are removed;
      * FTS and the search index are rebuilt.

   The outcome is SALVAGED, or RECREATED when nothing was readable. The old file is always kept.

The outcome, previous version, timing, error and salvaged row counts are in the event journal
and in the Database check.

`DatabaseIntegrity`:

* **`check`** runs:
  * `quick_check` and `foreign_key_check`;
  * an FTS integrity check;
  * JSON validity of caches and metadata;
  * row counts and size.
* **`repair`**:
  * deletes rows with broken references;
  * deletes unreadable cache rows;
  * resets corrupted metadata and schedules it for refresh;
  * rebuilds the FTS tables and re-queues the search index;
  * checkpoints the WAL.

No schema change in this phase (v8). The functional audit later added v9, an index only; see [AUDIT.md](AUDIT.md).

## Retry system

* `RetryPolicy` + `retrying {}` (core:common):
  * exponential backoff with jitter;
  * transient errors only (network, timeout, rate limit, 5xx);
  * Retry-After is honoured;
  * cancellation is never swallowed.
* Presets: `INTERACTIVE`, `BACKGROUND`.
* Existing layers keep their own retries:
  * Media3 load retries (`TelegramLoadErrorHandlingPolicy`);
  * WorkManager backoff (indexing 30 s exponential, metadata 5 min exponential, maintenance retry ×3);
  * provider circuit breakers.
* `PlaybackRecoveryPolicy` (pure, tested):

  | Error | Action |
  |---|---|
  | network | retry 2 s, 4 s, 8 s (max 30 s) while the network is up; wait while it is down |
  | file gone, unsupported or decoder | switch to another version (×2) |
  | unknown | retry once, then switch, then give up |

  The player shows the recovery in progress and keeps a recovery log.

## Background work and battery

| Work | Constraints |
|---|---|
| Chat indexing (user-requested) | network |
| Periodic index sync (6 h) | network, battery not low |
| Re-parse after parser upgrade | battery not low |
| Metadata sync (12 h) | network, battery not low, storage not low; exponential backoff |
| **Maintenance** (weekly, new) | **charging, device idle**, battery not low, storage not low |

* Maintenance runs the integrity check, repairs when needed, and trims the watch history.
* The Battery check shows battery level, saver, doze and optimization exemption.
* Battery-saver state is reported as the reason background work is waiting.

## Logging

* `AppLogger` writes to Logcat, an in-memory ring (debug screen) and a **persistent rotating
  file** (`files/diagnostics/logs/app*.log`, 3 × 512 KB).
  * File writes run on a single background thread; I/O failures are counted, never thrown.
  * Debug lines are written to the file in debug builds only.
* **Redaction on write:**
  * phone numbers (prefix and last digits kept);
  * `api_key=` / `token=` / `hash=` / `code=` parameters;
  * bearer/basic tokens and API-key headers;
  * JSON secret fields, login codes, long hex secrets.

  The caller rules from `Logger` still apply.
* Warnings and errors are also mirrored into the **event journal** (below), categorised by tag.

## Diagnostics

`:core:diagnostics` is pure JVM, so it works when Android parts are broken.

* **Event journal**: structured events (time, severity, category, message, exception
  class + root cause).
  * Categories: STARTUP, TELEGRAM, NETWORK, METADATA, CATALOG, DATABASE, PLAYBACK, INDEXING,
    WORK, STORAGE, MEMORY, CRASH.
  * Persisted, so the events before a crash can be read after restart. Per-run problem counts.
* **Crash reports**: `files/diagnostics/crashes/crash-<time>.txt`, the newest 10 kept. Each
  holds the summary, thread, device and app info, heap, stack trace and the last 80 log lines.
* **Safe mode** (`StartupGuard`):
  * Detection: a marker is written at start and removed after 30 s of running, so a start that
    dies early is counted even for native crashes or kills.
  * Safe mode starts after 3 early deaths in a row, or 3 crash reports within 10 minutes. It
    can also be forced from the screen.
  * In safe mode only the database opens; Telegram, indexing and background work don't start.
    Browsing and diagnostics work.
  * A safe-mode start that survives 30 s clears the counter.
* **Health checks** (`HealthCheck`, run concurrently by `DiagnosticsRunner`; each bounded to
  5 s, and one that throws or hangs is reported as FAILED):

  | Check | Covers |
  |---|---|
  | database | open outcome, integrity, counts, backups and quarantine |
  | telegram | status, connection |
  | network | transport, metered, validated |
  | metadata providers / catalog providers | per-provider state and last error |
  | background work | WorkManager states, failed jobs |
  | memory | heap, native, device, low-RAM, last trim level |
  | storage | free space, Telegram cache, DB size |
  | battery | level, charging, saver, doze, exemption |
  | crashes | reports, safe mode |
  | logging | size, write failures, problem counts |

* **Report**: one redacted text with device/app info, check results, crashes, recent events and
  the log tail. It contains no library contents beyond counts. It is exported to app storage and
  the clipboard.

### Diagnostics screen (Stage 1, unstyled)

**Diagnostics** tab:

* **Actions:** Run checks · Repair database · Export report · Trim memory · Force/Leave safe
  mode · Clear logs · Test crash (debug builds).
* **Sections:**
  * Checks (expand for details);
  * Events (severity filter);
  * Crashes (open the full report);
  * Log tail;
  * Device.

The main screen also shows a safe-mode banner and an **Interrupted playback → Resume** banner.

## Tests

* **`:core:diagnostics`:**
  * redaction;
  * log rotation and write failures that never throw;
  * the journal persisting and reloading across restarts;
  * crash store pruning and crash-loop/safe-mode detection;
  * a runner with throwing and hanging checks;
  * the report;
  * the playback session store (survives a "restart", expires, damaged file).
* **`core:common`:** retry backoff, transient vs permanent errors, Retry-After, `guarded`.
* **`DatabaseSafetyTest`** (Robolectric, real files):
  * create/reopen;
  * v7 → v8 upgrade with a v7 backup;
  * an unopenable database (v99) salvaged with progress, favorites and dismissals intact;
  * a garbage file → working empty DB, file quarantined;
  * integrity check and repair of broken references, unreadable cache and corrupted metadata.
* **Playback:**
  * the recovery policy;
  * a deleted file switching to another version and playing to the end;
  * a network outage retried automatically with backoff.

  Existing playback tests run with automatic recovery off, so they keep testing the manual paths.
* **Providers:** changed TMDB response formats; a provider that throws falls back instead of crashing.

## Limits

* The database opens on a background coroutine at startup. A screen that needs it before that
  finishes waits for it: an upgrade of a very large database still delays the first screen, but
  no longer happens on first query in the middle of the UI.
* Safe mode cannot protect against a crash *inside* the database open itself. That is why the
  open path is the most defensive code in the app.
* Native (TDLib) crashes produce no Java crash report. They are detected through the startup
  marker and TDLib's own log file.
