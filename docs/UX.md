# Advanced user experience (Stage 2, step 4)

Features built on top of the completed functionality. Each one is listed with what it does for
the user, how to measure that, and what (if anything) leaves the device.

**Principles.**

* The core library needs no account on any third-party cloud service. Telegram (where the files
  are) is the only service the app needs. Metadata providers (TMDB, Cinemeta) are optional and
  receive only a title and year.
* Everything "smart" is deterministic and local: parsers, policies and SQL over the device's own
  database. There is no cloud AI, no telemetry and no model download. An on-device `IntentModel`
  remains an optional hook (see [ASSISTANT.md](ASSISTANT.md)); nothing requires it.
* A feature is shown only where it saves the user a step or answers a real question.

## Overview

| # | Feature | Where | Status | Measurable value | Leaves the device |
|---|---|---|---|---|---|
| 1 | Automatic library: files → matching → posters → movies/series → seasons → episodes | Library status, Check matches, Fix match | pipeline existed (P2–P5); **new**: status screen, review queue UI, Fix match | % of titles with posters (`matched / titles`); titles waiting for review → 0; time from a new file to a playable title | title + year to the metadata provider (if configured) |
| 2 | Smart Continue Watching | Home row, hero, screen | existed (P12) | resumes within 1 tap; finished/abandoned items never shown | nothing |
| 3 | One-click next episode | Home hero, Details, player (next button, up-next countdown), Media Brain "play …" | existed; **new**: one-tap play from Media Brain results | taps from opening the app to the next episode: 1 | nothing |
| 4 | Intelligent language selection | player | existed (P9) | track switches per session; remembered per series | nothing |
| 5 | Smart search | Search | existed (P10) | typo/transliteration hits ("doremon" → Doraemon) | nothing |
| 6 | Natural-language Media Brain | Search | engine existed (P11); **new**: answers in Search | requests answered with hard filters instead of text matches ("unwatched Tamil movies") | nothing |
| 7 | Recently Added | Home row, screen | existed | new arrivals visible without browsing chats | nothing |
| 8 | Watch statistics | Settings → Watch statistics | **new** | — (information the user asked for) | nothing |
| 9 | Recommendations from local history | Home "For you" | engine existed; **new**: discovery-only API + Home row with reasons | share of watched titles started from "For you" | nothing |
| 10 | Telegram library synchronization | Library status ("Sync now", per-source state) | indexer existed (P2); **new**: visible progress and errors | files indexed, last sync time, failed sources visible | Telegram API calls (as before) |
| 11 | Multi-device progress sync (optional) | Settings → Sync across devices | **new** | resume position matches across devices after sync | one end-to-end encrypted file in the user's own Saved Messages |

## 1 and 10: the automatic library, made visible

`LibraryStatus` (pure, tested) turns the domain's raw state into four stages:

| Stage | Source | Shown |
|---|---|---|
| From Telegram | `MediaIndexRepository.observeChats` | files, syncing / "checked 5 minutes ago", per-source errors, "42 files found so far" while running, **Sync now** (`MediaIndexer.scheduleAll` + `MetadataSyncTrigger.requestRun`) |
| Recognised titles | `MediaRepository.count` by kind | "180 movies · 60 series" |
| Posters and details | `MetadataRepository.statusCounts` | "204 of 240" with a progress line; "Matching 6 more…"; retries; "no provider set up" |
| Needs your check | `NEEDS_REVIEW` count | **Review** button; titles kept with file-name details |

A privacy note at the end states what is sent, and to which providers.

**Check matches** (`MatchReviewViewModel`): the matcher never applies a suggestion below its
acceptance threshold (0.80). Those titles are queued with candidates. For each title the screen
shows what the file name says, the candidates as posters with their match percentage, and three
choices:

* tap a candidate: `ConfirmMetadataMatch`;
* **None of these**: `RejectMetadataMatch`, which keeps file-name details and never suggests again;
* **Search…** by title and year: `SearchMetadataCandidates`.

User choices are final: the matcher never overrides them.

**Fix match** (Details → Fix match → `FixMatchRoute`) is the same screen for any one title.

On Home, a low-priority banner ("12 titles need a quick check") appears only when no sign-in,
source, offline or indexing banner applies. Settings shows the count too.

## 6: Media Brain in Search

Every query runs the title search **and** the local assistant side by side.
`MediaBrain.isRequest` (pure, tested) decides whether to show the assistant's answer. It does so
for an explicit intent, or for any recognised filter: type, language, genre, years,
season/episode, watched state, sort, count or resolution. For a bare title the normal search
stands alone.

When it answers, Search shows:

* the domain's own sentence ("4 unwatched Tamil movies.");
* what it understood, as chips ("language Tamil", "unwatched", "movies");
* the results;
* for a resolved play request, a **Play now** button.

Episodes and "continue" picks open the player directly. Title matches that are not already in
the answer follow under "Title matches", so a title that looks like a filter ("1917") is still
found.

## 9: For you

`MediaAssistant.recommendations(limit)` returns discovery picks only: unwatched, available titles
that aren't hidden and haven't been started (Continue Watching covers those). They are ranked by
the local watch profile: language, genre and type shares of recent viewing, plus rating. Each
pick has a reason ("Telugu · action · ★ 7.6"), shown on the card, and the row says what the
profile is built from. The row is recomputed when the viewing history or the library changes.
With no history, the picks are simply the highest-rated unwatched titles. The ranking is the
same one "what should I watch" uses (tested).

## 8: Watch statistics

`WatchStatsCalculator` (pure, `:watch:core`, tested) over `WatchDao.observeSessions` (the
device's own sittings, joined to kind, languages and genres):

| Shown | Definition |
|---|---|
| Last 7 days, trend | viewing time vs the 7 days before |
| All time, sittings | total viewing time; number of sittings |
| Finished | distinct movies and episodes completed |
| Streak | consecutive days with ≥ 1 min of viewing, ending today (or yesterday); longest ever |
| Last 14 days | one bar per day, today right; described in words for screen readers |
| Languages / genres | share of viewing time; a title in two languages splits its time |
| Footer | titles watched, average sitting, the usual hour |

Viewing time of a sitting is the wall time from its start to its last progress save, capped at
the file's duration (6 h when unknown). The statistics are never synced or uploaded.

## 11: Optional multi-device progress sync

**Off by default.** Without it, the app reads nothing from and writes nothing to Telegram
beyond indexing and playback.

| Question | Answer |
|---|---|
| Where is it stored? | One document in the user's own Telegram **Saved Messages**, tagged `#yourstream_progress`. No other account or cloud service. Other messages are never read or changed. |
| Who can read it? | Only devices that know the sync passphrase. AES-256-GCM, random nonce per write, format header authenticated. The key is PBKDF2-HMAC-SHA256 (210 000 iterations) over the passphrase, salted with the Telegram account id. Telegram stores ciphertext only. |
| What is in it? | Per unit: the Telegram file's unique id and/or the title's match key with season/episode, the position, the duration, whether it is finished, a play count and a timestamp. No viewing history, no statistics, no titles in clear text beyond the match key inside the ciphertext. |
| How do devices agree on "the same episode"? | Local ids differ per device. Units are identified by the Telegram file unique id (the same for every copy of an upload), else by the title match key + season/episode from file-name parsing. |
| Conflicts? | Per unit, the newer record wins. On a tie, finished beats unfinished, then the further position. Older progress never rewinds newer local progress. Units a device cannot resolve are kept, so devices with different sources don't erase each other. |
| When does it run? | At app start, a minute after progress changes, and on **Sync now**. |
| The passphrase? | Typed once per device, never stored or saved; only the derived key is kept, wrapped by a non-exportable Android Keystore key. A passphrase that cannot read the existing snapshot is refused before anything is enabled. |
| Turning it off | Optionally deletes the shared snapshot too. Local progress always stays. |

Modules:

* `:sync`: `SyncCrypto`, `ProgressSnapshot` / `ProgressMerge` (pure), `ProgressSyncStore`
  (export/apply via `ProgressSyncDao`), `ProgressSync`, `AndroidSyncSettings`.
* `:telegram:api` `TelegramSavedMessages`: find, read, replace, delete. Implemented in
  `:telegram:core` over four new `TelegramClient` calls (Saved Messages chat id, document search,
  send document, delete messages).

No database schema change: one new DAO and query only.

## Tests

* **Pure:**
  * statistics (totals, trend, streaks, caps, shares);
  * library pipeline counts;
  * the Media Brain request rule;
  * brain tiles (one-click targets, reasons);
  * sync crypto (round trip, wrong passphrase, tampering, account salt);
  * merge rules;
  * snapshot format.
* **Database (Robolectric):**
  * statistics from real watch history;
  * previous episode;
  * recommendations exclude anything started, watched or hidden;
  * **two devices**, each with its own database and different local ids, sharing one Saved
    Messages: progress moves both ways, newer wins, a wrong passphrase is refused, turning off
    deletes the shared copy, nothing is sent while off.
* **Telegram:** the Saved Messages store keeps one tagged copy, never touches other messages and
  enforces the size limit.
* **ViewModels:** Home "For you" and the review banner; Search's brain answer vs a bare title.
* **Compose:** library status, match review (confirm / none / search), statistics, sync (off by
  default, passphrase minimum, problems, turn off), Media Brain card with Play now.
* **Screenshots:**
  * `phone-library-status`;
  * `phone-review`;
  * `phone-stats`;
  * `phone-sync`;
  * `phone-search-brain`;
  * `phone-home-dark` (now with "For you" and the review banner).
