# Media discovery and indexing (Phase 2)

```
Telegram (TDLib)
  → Chat scanner      DefaultMediaIndexer / IndexScheduler   which chats, when (WorkManager)
  → Message scanner   IndexingEngine                          searchChatMessages per filter, cursors
  → Media extractor   TdMappers.mediaMessage                  MessageVideo / MessageDocument / MessageAudio
  → Filename parser   :media:filename MediaFilenameParser      see PARSER.md
  → Media normalizer  MediaClassifier + MediaNormalizer       type, extension, fingerprint, search text
  → Local database    RoomIndexStore → core:database (Room)   upsert, duplicates, FTS
```

| Module | Role |
|---|---|
| `:telegram:api` / `:telegram:tdlib` | `TelegramMessageRepository`: `searchMedia`, `lookupMessages`, live `events`. TDLib stays in `:telegram:tdlib` |
| `:media:api` | `MediaIndexRepository` (read, paged), `MediaIndexer` (write/schedule), models |
| `:media:filename` | Standalone filename parser (see [PARSER.md](PARSER.md)) |
| `:media:parser` | Pure Kotlin: `ParsedMediaMapper`, `MediaClassifier`, `MediaNormalizer`, `SearchText` |
| `:core:database` | `YourStreamDatabase` (Room, schema in `core/database/schemas`) |
| `:media:indexer` | `IndexingEngine`, `RoomIndexStore`, `LiveIndexUpdater`, workers, use cases, Hilt |

## Database schema (version 2; v1 → v2 adds parsed-metadata columns, see PARSER.md)

| Table | Key | Purpose |
|---|---|---|
| `indexed_chat` | `chat_id` | Chats chosen for indexing: `enabled`, `state`, `last_error`, `last_indexed_at`, `last_verified_at`, `verify_cursor` |
| `scan_cursor` | (`chat_id`, `filter`) | Per Telegram filter (VIDEO/DOCUMENT/AUDIO): `newest_message_id` (high-water mark), `backfill_from_message_id`, `backfill_complete`, `estimated_total` |
| `media` | `id`; unique (`chat_id`, `message_id`) | One file per message: ids (`file_id` session-local, `file_unique_id`, `remote_file_id`), name, MIME, extension, size, duration, width, height, codecs, type, how it was sent, caption, audio tags, download state/bytes/path, `search_title`, `fingerprint`, `canonical_media_id` + `duplicate_kind`, `is_deleted`/`deleted_at`, `indexed_at`/`updated_at`/`last_seen_at` |
| `media_parsed` | `media_id` → media (cascade) | Parser output: title, year, season, episode(-end), resolution, source, codecs, HDR, languages, subtitle languages, edition, group, part, confidence, `parser_version` |
| `media_fts` | rowid = `media.id` | FTS4 (unicode61) over `file_name`, `caption`, `search_title`. External content; Room's triggers keep it in sync |
| `index_run` | `id` | Run history per chat (mode, outcome, pages, seen, added/updated/deleted, error). The last 20 per chat are kept |

Indexes on `media`: (`chat_id`,`message_id`) unique, (`chat_id`,`is_deleted`) for counts, (`chat_id`,`message_date`) and `message_date` for ordering, `file_unique_id` and `fingerprint` for duplicate lookups, `canonical_media_id`, `file_id` (download updates), `media_type`.

Deleting an `indexed_chat` row cascades to its cursors, media, parsed rows (and so FTS) and runs.

## Indexing algorithm

One engine run handles one chat in one mode (`INCREMENTAL`, `FULL_RESCAN`, `VERIFY`). For each filter VIDEO, DOCUMENT and AUDIO:

1. **Head pass** (new media). It runs when the cursor has a high-water mark `N > 0`, or when a finished backfill found nothing.
   - Page from the newest message (`fromMessageId = 0`) towards older ones.
   - Store only messages with id > N.
   - Stop as soon as a page reaches id ≤ N, or Telegram has no more.
   - Only then raise N to the newest id seen. An interrupted head pass therefore repeats instead of leaving a gap.
2. **Backfill** (history). It runs until `backfill_complete`.
   - Continue from `backfill_from_message_id`; the first ever page also sets N.
   - Each page's media **and** the advanced cursor are written in **one transaction**, so the cursor never moves past media that weren't stored.
   - `nextFromMessageId == 0` marks the backfill complete.
3. **Verify** (deleted media). It runs in `VERIFY` mode, or when the last check is over 24 h old, or when a previous check is unfinished.
   - Walk the chat's live records with a keyset cursor (`message_id > verify_cursor`, 100 at a time) and call `getMessages`.
   - Missing messages, or messages whose file was edited away, are marked deleted.
   - Live messages are re-upserted, which catches replaced files and refreshes session file ids.
   - An incremental run checks at most 2,000 messages and resumes next time; `VERIFY` checks all.

Extraction and normalisation happen per message:
- `TdMappers` turns video, document and audio messages into `TelegramMediaMessage`; anything else is ignored.
- `MediaClassifier` decides the type from extension, then MIME. Telegram often sends MKVs and SRTs as `application/octet-stream`. Split parts like `.mkv.001` are classified as video.
- `IndexingPolicy` keeps video, subtitles and audio by default. Archives and other documents (PDF, APK…) are skipped.
- `MediaFilenameParser` parses the file name, falling back to the caption and then the chat title for generic names. The original `file_name` is stored unchanged.

Running and background work:
- **Live updates** (`LiveIndexUpdater`): while the app runs, new media in enabled chats are inserted immediately, permanent deletions are applied, and edited messages are re-looked-up. Live inserts deliberately do **not** move cursors: they say nothing about messages missed while the app was closed.
- **Background** (WorkManager):
  - Each chat has one unique work chain, `index-chat-<id>`. It needs a network connection, retries with exponential backoff, and survives process death and reboot.
  - A run has an 8-minute budget; if it runs out, the run saves its position and queues its own continuation.
  - `PeriodicSyncWorker` queues an incremental run for every enabled chat every 6 hours, when the battery is not low.
  - At most 2 chats scan at once, process-wide.
- **Retry policy**, per request:
  - Network errors and timeouts are retried 5 times with exponential backoff plus jitter.
  - A FLOOD_WAIT of up to 120 s is waited out. A longer one ends the run with `RETRY`, and WorkManager backs off.
  - Not authorised, or chat inaccessible: `FAILED`, with the error stored in `indexed_chat.last_error`.
  - There is a 200 ms pause between pages to stay clear of flood limits.
- **Stopping**: cursors are saved per page, so any stop loses at most the page in flight, and the
  next run continues from the cursors (nothing is scanned twice).

### States and controls (since 0.11)

| State | Meaning |
|---|---|
| `IDLE` | Never ran, or nothing pending |
| `QUEUED` | Waiting: its turn, the network (or Wi-Fi), the battery, or the next part of a long scan (8-minute budget used) |
| `CONNECTING` | A run started and waits for Telegram to be signed in and connected |
| `SCANNING` | Reading messages: new ones, the older backlog, or re-checking known files |
| `PROCESSING` | Reading titles from the posts above untitled files |
| `COMPLETED` | Up to date |
| `PAUSED` | Paused by the user (`indexed_chat.paused`, v13): nothing runs until resumed |
| `FAILED` | Stopped on an error with no automatic retry left (`last_error` says why) |
| `CANCELLED` | The user stopped this run; the next sync continues from where it stopped |
| `RECOVERING` | Interrupted (network error, app killed): WorkManager runs it again from the cursors |

- **Pause / Resume** (per chat, or all, in Library status): pause sets the flag and stops queued and
  running work; `schedule`, `scheduleAll` (periodic sync, "Sync now") and the worker skip paused
  chats. Resume clears the flag and queues an incremental run. Live updates still apply to paused
  chats (they read nothing).
- **Stop** (`cancel`): ends the current run (`CANCELLED`); the next sync picks the chat up again.
- A system stop (constraints no longer met, policy change) ends in `QUEUED`; a time-budget stop
  too, with its continuation queued. A network error ends in `RECOVERING` (with the reason); the
  worker marks `FAILED` only when its retries run out.
- At start, active states left by a killed process (and `RUNNING` from before v13) become
  `RECOVERING`; `PAUSED` without the flag (the old meaning: time budget) becomes `QUEUED`.
- **When indexing may run** (Settings → Content & discovery → Indexing; stored by `IndexScheduler`,
  a device setting): *Index on Wi-Fi only* (`NetworkType.UNMETERED`, off by default) and *Wait while
  the battery is low* (`requiresBatteryNotLow`, on by default). Changing them reschedules waiting
  and running work under the new conditions. The periodic sync never runs on a low battery.

## Titles from the post above a file

Movie channels usually post the title first (a poster with a caption, or a text post) and then
the files, captioned only "Tel+Hin 450MB" or nothing, with machine-made file names. For a video
whose own name and caption carry no title (the parser finds none, only a language list or a
channel name, or only its chat's name), the engine reads the chat's history from the file towards
older messages (`ContextFinder`): the group's other files are skipped, promotions ("Join @…",
links) are not titles, and the walk stops after 12 messages or 6 hours. The first text that names
a title becomes the file's context (`media.context_caption`, database v12; "" = looked, none):
the parser takes title, year and season from it, and the episode from the file.

- One page of history (100 messages) serves the neighbouring files: a channel costs about one
  request per 100 messages that need it.
- Re-checks and live updates pass the stored context back in, so they never lose the title.
- Files indexed before (or whose lookup failed) are looked up by the next runs, newest first, up
  to 5,000 per run and chat (`IndexPhase.TITLES`).
- A failed request stops lookups for the rest of the run (no hammering); they are retried later.

## Incremental indexing, by example

A chat with 50,000 messages, 3,000 of them media, gets 20 new files overnight:

- **First run:** about 30 pages per filter. If the 8-minute budget runs out, the run is continued automatically from the saved cursor.
- **Next run:** one page per filter from the top, about 3 requests. It sees the 20 new files and a known id, and stops. The backfill is already complete, so it does nothing else. Once a day it also checks up to 2,000 old messages for deletion.

## Duplicate detection

| Situation | How it is handled |
|---|---|
| The same message seen again (overlapping pages, rescan, live update + scan) | Upsert on the unique key (`chat_id`, `message_id`). Never a second row |
| The same upload in several messages or chats (forwards) | Same Telegram `file_unique_id` → **EXACT** |
| A re-upload of the same file | Same `fingerprint` = byte size + normalised base name + extension + split part, only for files ≥ 1 MB → **PROBABLE** |

A duplicate group is a star:
- Every duplicate's `canonical_media_id` points at the group's oldest live row.
- `includeDuplicates = false` in a query hides non-canonical rows.
- When the canonical row is deleted, the group is dissolved and its survivors are re-grouped, oldest first. Each `duplicate_kind` then again describes the relation to the new canonical row.

## Performance

- **Nothing is loaded in bulk.** The engine holds one page (≤100 messages). Search and browse return `PagingData` straight from Room `PagingSource`s (pages of 50, at most 400 rows kept). Counts are `COUNT(*)` on indexes.
- **Writes are batched.** One transaction per page, one prepared statement per row, inside SQLite's WAL journal. The 20,000-row test inserts, parses and FTS-indexes in about 6 s on the host JVM.
- **The deletion check uses keyset pagination**, never `OFFSET`, so its cost per batch stays flat however large the chat is.
- **Full-text search** uses FTS4 with prefix terms (`aven* endg*`), not `LIKE '%…%'` table scans.
- **Rows store compact lists** as comma-joined text; the parser output is a separate 1:1 table so it can be recomputed (`parser_version`).
- **List queries read a narrow projection** (`MediaSummaryRow`); the full record is fetched only when one item is opened.

## Manual test path (real device)

1. Sign in, then **Settings → Content & discovery → Telegram sources**: choose a chat with videos.
2. **Settings → Content & discovery → Library status**: the chat shows "Connecting to Telegram…",
   then "N files found so far", then the time of the last sync.
3. Tap **Pause** on a large chat while it scans: "Paused". Close and reopen the app, and wait for
   the next periodic sync: it stays paused. Tap **Resume**: it continues, and the file count only
   grows by files it had not seen.
4. Tap **Stop** while it scans: "Stopped — continues at the next sync". **Sync now** continues it.
5. Turn on **Index on Wi-Fi only**, switch to mobile data, tap **Sync now**: "Waiting for Wi-Fi".
   Connect to Wi-Fi: it starts by itself.
6. Airplane mode during a scan: "Interrupted — continuing soon"; it continues when back online.
7. Force-stop the app during a scan, reopen: "Interrupted — continuing soon", then it continues.
8. Post a new file in the chat from another device: with the app open it appears at once (live
   update), even while the chat is paused.
