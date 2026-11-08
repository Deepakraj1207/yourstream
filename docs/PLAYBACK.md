# Playback engine (Phase 7)

Plays Telegram media with AndroidX Media3 / ExoPlayer **while the file is being downloaded**,
with random access: a seek moves the Telegram download to the new position instead of fetching
everything in between. TDLib stays inside `:telegram:tdlib`; Media3 never sees a TDLib type.

```
 Library (PlaybackTarget: item / episode)
   │
   ▼
 MediaSourceResolver ── LibraryMediaSourceResolver (:playback:media3)
   │   picks the version, re-reads the Telegram message (fresh session file id),
   │   checks the file is still obtainable → PlayableMedia → MediaItem(tgfile://file/<id>?size&durationMs)
   ▼
 TelegramMediaSourceFactory (Media3 MediaSource.Factory)
   │   ProgressiveMediaSource + DefaultExtractorsFactory + TelegramLoadErrorHandlingPolicy
   ▼
 TelegramMediaDataSource (Media3 DataSource, one per load, on Media3's loader thread)
   │   open(position) / read() / close(); blocking, interruptible
   ▼
 TelegramFileStreamer ─ FileSession (one per file: download window, counters)   (:playback:stream, pure JVM)
   │   └─ TelegramFileStream (positioned reader) ─ LocalPartReader (direct file read | readFilePart)
   ▼
 TelegramFileRepository (:telegram:api)
   │   download(offset, limit) · downloadedPrefixSize(offset) · readPart · deleteLocalCopy · fileUpdates
   ▼
 TelegramClient port → TdlibTelegramClient (:telegram:tdlib): downloadFile, getFileDownloadedPrefixSize,
                                                               readFilePart, deleteFile, updateFile
   ▼
 Telegram servers

 ExoPlayer ◀── TelegramPlaybackController (state, metrics, retry, progress, release) ◀── test player UI
```

## 1. Modules

| Module | Kind | Contents |
|---|---|---|
| `:telegram:api` | JVM | `TelegramFileRepository` gained the range primitives: `downloadedPrefixSize(fileId, offset)`, `readPart(fileId, offset, count)`, `deleteLocalCopy(fileId)` (plus the existing `download(fileId, priority, offset, limit)` and `fileUpdates`) |
| `:telegram:core` / `:telegram:tdlib` | JVM / Android | Port methods `getFileDownloadedPrefixSize`, `readFilePart`, `deleteFile`, mapped to TDLib (10 s timeout: they are local file-database queries) |
| `:playback:stream` | JVM | `TelegramFileStreamer`, `TelegramFileStream`, `StreamingConfig`, `StreamHints`, `TelegramStreamException`, `StreamCache`, `StreamStats` |
| `:playback:media3` | Android | `TelegramMediaDataSource`, `TelegramMediaSourceFactory`, `TelegramMediaUri`, `TelegramLoadErrorHandlingPolicy`, `MediaSourceResolver` + `LibraryMediaSourceResolver`, `PlayableMedia`, `TelegramPlaybackController`, `PlaybackState/Error/Metrics/Config`, Hilt `PlaybackModule` |
| `:playback:testing` | JVM | `SimulatedTelegramFiles` (TDLib-like backend with bandwidth, latency, outages), `TestMedia` (synthetic videos) |
| `:app` | | `PlayerActivity` (minimal test player), ▶ buttons in the Library debug tab |

Only `:playback:media3` and `:app` know Media3. Only `:telegram:tdlib` knows TDLib.

## 2. How a byte gets to the decoder

1. **Resolve.** `LibraryMediaSourceResolver.resolve(target, mediaId?)` chooses the version:
   explicit `mediaId`, otherwise the best one (not deleted or excluded, original before
   duplicate, highest resolution, then largest). It then looks the message up again
   (`lookupMessages`), because Telegram file ids are only valid within one TDLib session and
   the message may be gone. Offline, it uses the indexed file id, but only if Telegram still maps
   it to the same `remoteUniqueId`.
2. **MediaItem.** `PlayableMedia.mediaItem()` builds `tgfile://file/<fileId>?size=…&durationMs=…`.
   The size and duration hints let the streamer size its download window before the first byte
   arrives.
3. **Open.** ExoPlayer's `ProgressiveMediaPeriod` opens `TelegramMediaDataSource` at a byte
   position: 0 at start, the `moov` position for a moov-at-end MP4, or the offset of the target
   sample after a seek. That becomes `TelegramFileStreamer.open(fileId, position)`. Opening is
   cheap: it gets the file state, creates or reuses the file's `FileSession`, and fails fast if
   the file is unavailable or the position is out of range.
4. **Read.** `TelegramFileStream.read()`:
   - If the bytes at `position` are known to be on the device (`position < availableEnd`), it
     reads them directly. No Telegram call is made.
   - Otherwise `awaitData(position)` asks how many bytes are there (`downloadedPrefixSize`).
     Inside the current contiguous download window this is answered from the last file update
     without a TDLib round trip. If nothing is there yet, it makes sure a download runs at
     `position` and suspends until a file update or a 150 ms poll shows data.
   - Bytes are read from the partially downloaded file with `RandomAccessFile`, because TDLib
     writes each part at its real offset. `readFilePart` is the fallback when the path is
     unknown or unreadable. FileChannel is avoided on purpose: Media3 cancels loads by
     interrupting the loader thread, and an interrupted FileChannel closes itself.
   - After each read, `maintainWindow` keeps the download ahead of the reader (§3).
5. **Close.** Each `DataSource.close()` closes the stream. The download window stays, because
   the next open (a seek or the next loadable) usually follows immediately.
6. **Release.** `TelegramPlaybackController.stop()/release()` calls
   `TelegramFileStreamer.release(fileId)`: it cancels the Telegram download and hands the file to
   the `StreamCache` (§7).

## 3. Seeking optimisation: the sliding download window

TDLib's `downloadFile(fileId, priority, offset, limit)` starts downloading at any offset and
stops after `limit` bytes. A new call with another offset replaces the running download. Parts
already on the device are skipped, and `getFileDownloadedPrefixSize(offset)` works for any
offset. The streamer uses this as follows:

- **Window.** Telegram is only ever asked for `[first missing byte at the reader, reader + readAhead)`.
  `readAhead` comes from the bitrate: 90 s of playback, clamped to 8–96 MB, or 32 MB when the
  bitrate is unknown. Priority is 32, the maximum, so playback beats background downloads.
- **Seek re-points the download.** `maintainWindow` compares Telegram's download head
  (`downloadOffset + downloadedPrefixSize` from the file updates) with the byte the reader
  needs. If Telegram is downloading somewhere else, the download is re-pointed. "Somewhere
  else" means ahead of the target (a backward seek), or more than `maxGapBytes` (2 MB) behind it
  (a forward seek, even one inside the current window). The bytes in between are never
  requested.
- **Extension.** When less than half of the window is still requested ahead of the reader, the
  window is extended from the first missing byte. In steady playback that is one TDLib call per
  `readAhead / 2` bytes.
- **Bounded background download.** When the reader stops (pause, or Media3's buffer is full),
  Telegram stops at the end of the window. A paused 4 GB movie does not download itself in the
  background.
- **Seek back.** Data already downloaded is served from disk with no network: TDLib keeps every
  part, and the prefix query finds it.
- **Container index.** Media3 needs an index to turn a time into a byte offset. MP4 has
  `moov`/`stbl` (or `sidx`) and MKV/WebM has `Cues`, so a seek to 01:20:00 opens the data
  source right at the target sample. A moov-at-end MP4 costs one extra jump to the end at
  startup. For index-less MP3/ADTS/AMR, constant-bitrate seeking is enabled. Exact seeking is
  the default. `setFastSeeking(true)` switches to `CLOSEST_SYNC`, which jumps to the nearest key
  frame and decodes less, for scrubbing.

Measured (simulated network, §9): seeking from 00:05 to 01:20:00 in an 85-minute, 148 MB file
downloads nothing between 20% and 85% of the file. In total, about 0.8% of the file was
downloaded after startup plus both seeks. In the raw engine test (200 MB file, jump to 94%),
0.44% was downloaded and no part of the gap was fetched.

## 4. Buffering

Two levels:

| Level | Where | Bounds |
|---|---|---|
| Disk read-ahead | TDLib file cache, managed by the window | 8–96 MB ahead of the reader (≈ 90 s of playback) |
| Memory buffer | Media3 `DefaultLoadControl` | 15–50 s of media, **capped at 32 MB** (`setTargetBufferBytes`, time not prioritised over size); playback starts after 1 s buffered, resumes after a stall at 2.5 s |

`ProgressiveMediaSource.setContinueLoadingCheckIntervalBytes(512 KB)` makes Media3 check the load
control often, so memory stays near the cap even with high-bitrate (4K) files.

## 5. State, position, duration, completion

`TelegramPlaybackController` (main thread) wraps one ExoPlayer and publishes:

- `state: StateFlow<PlaybackState>` with fields: status (IDLE → PREPARING → READY ⇄
  BUFFERING → ENDED, or ERROR), `playWhenReady`, `isPlaying`, `positionMs`, `durationMs` (null
  until known), `bufferedPositionMs`, video size, `seeking`, current `PlayableMedia`, and
  `PlaybackError`. It is updated on every player event and sampled every 500 ms. `snapshot()`
  reads the player directly.
- **Completion.** `STATE_ENDED` saves the progress as position = duration, so the library marks
  the item watched (its ≥ 90% / last 3 minutes rule). Progress is also saved every 10 s while
  playing, on pause, on stop and on release, through `PlaybackProgressSink`
  (`WatchHistoryRepository.updateProgress` in the app).
- **Commands.** `open(target, mediaId?, startPositionMs?)` (the test player resumes from the
  saved position), `play(media)`, `resume`, `pause`, `togglePlayPause`, `seekTo`, `seekBy`,
  `setFastSeeking`, `retry`, `stop`, `release`.

## 6. Errors, retry, network loss, cancellation, availability

| Situation | Detected by | Result |
|---|---|---|
| Message deleted / file gone before playback | resolver: lookup Missing/NoMedia, `canBeDownloaded = false` | `AppError.NotFound`; state ERROR `FILE_UNAVAILABLE`, not retryable; the player is never started |
| File becomes unavailable during playback | `getFile` NotFound / not downloadable | `TelegramStreamException.Unavailable` → Media3 `ERROR_CODE_IO_FILE_NOT_FOUND`; policy: no retry; `FILE_UNAVAILABLE` |
| Network down / very slow | no new data at the read position for `stallTimeoutMs` (20 s; extended while other bytes of the file arrive, max 3×) | `Stalled` (retryable) → `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` |
| Telegram error on a call | `AppResult.Err` | `Telegram(error)`; retryable for network/timeout/rate limit/5xx |
| Short outage (< stall timeout) | — | invisible to the player: the read just waits; at most one rebuffer |

- **Retry policy.** `TelegramLoadErrorHandlingPolicy` retries retryable failures with
  exponential backoff (1, 2, 4, 8, 8 s) up to `loadRetries` (5). Unavailable files fail
  immediately. Only then does the error reach the player.
- **Recovery.** `retry()` re-prepares, and playback continues from the same position. With
  `autoRetryOnReconnect`, the controller retries by itself, up to 3 times, when Telegram's
  connection state returns to READY.
- **Cancellation.**
  - *Loads:* Media3 cancels a load by interrupting the loader thread. The data source runs each
    stream call in `runBlocking`, so the interrupt cancels the suspended wait at once (measured:
    under 2 ms) and surfaces as `InterruptedIOException`, which Media3 treats as "cancelled".
  - *Playback:* `stop()`/`release()` cancels the Telegram download of the file.

## 7. Temporary caching

- The bytes of a stream live in **TDLib's file cache**, in the app's cache directory. That is
  the temporary cache. Seeking back, re-opening, or a second Media3 loadable reuses them for
  free.
- **No Media3 `SimpleCache`.** It would store every byte a second time, next to TDLib's copy.
- `StreamCache` bounds what streaming leaves behind. Default budget 2 GB, LRU over files
  streamed in this process. When over budget, the oldest copies get `deleteLocalCopy`.
  - Never deleted: the file being played, the most recently streamed one, or a file that was
    already completely downloaded before streaming (the user's own downloads).
  - Android may also clear the cache directory under storage pressure.

## 8. Threads

| Thread | Work |
|---|---|
| Main | `TelegramPlaybackController`, ExoPlayer API, state flows |
| ExoPlayer playback thread | renderers, load control |
| Media3 loader thread | `TelegramMediaDataSource` → blocking stream calls (interruptible) |
| `Dispatchers.Default` | streamer's file-update collector |
| `Dispatchers.IO` | progress saving, download cancellation on release |
| TDLib | its own threads; we only send requests and read the partial file |

## 9. Tests and measurements

All tests run without a device: a real ExoPlayer under Robolectric with Media3's shadow
codecs, and a simulated Telegram backend.

`SimulatedTelegramFiles` behaves like TDLib for streaming:
- aligned parts, one download per file defined by (offset, limit), a new call replaces it;
- parts already on the device are skipped, a sparse local file holds them, prefix sizes work
  at any offset;
- bandwidth, per-part latency and outages are real delays.

Test videos are synthetic, made by `scripts/make-test-media.sh` (ffmpeg test patterns and tones,
no third-party content). The small clips are committed:
- 640×360 faststart MP4;
- 1280×720 MP4 with the moov at the end;
- 1920×1080 MP4;
- 854×480 MKV.

The 85-minute, 148 MB H.264/AAC file is generated into `build/test-media` on first use when
ffmpeg is installed; without ffmpeg its tests are skipped.

| Requested test | Test(s) |
|---|---|
| small MP4 | `smallMp4PlaysToTheEndWithDurationPositionAndCompletion`, `sequentialReadReturnsTheExactFile` |
| large video | `largeVideoSeekFrom0005To012000DownloadsOnlyAroundTheTarget` (85 min / 148 MB), `seekingFarAheadDoesNotDownloadTheGap` (200 MB) |
| different resolutions | `differentResolutionsAndContainersIncludingMoovAtTheEnd` (360p/480p MKV/720p moov-at-end/1080p) |
| seeking | the large-video tests, `seekingBackIntoDownloadedDataNeedsNoNetwork` |
| pause/resume | `pauseHoldsThePositionAndResumeContinues`, `pausedReaderLimitsBackgroundDownloadToTheWindow` |
| network interruption | `shortNetworkInterruptionIsBridgedByBuffering`, `longNetworkInterruptionErrorsAndRetryResumesWhereItStopped`, `reconnectRetriesAutomatically`, `shortNetworkInterruptionIsBridged`, `longInterruptionFailsAsRetryableStallAndRecovers` |
| slow network | `slowNetworkStartsLaterButPlaysThrough` (1 Mbit/s, 250 ms RTT), `slowNetworkStillDeliversCorrectData` |
| repeated seeking | `repeatedSeekingEndsReadyAtTheLastTarget` (8 settled seeks + 5-seek scrub), `repeatedRandomSeeksReturnCorrectBytesAndStayBounded` (35 seeks, byte-exact) |
| cancellation / availability / cache | `interruptingABlockedReaderCancelsPromptly`, `releaseCancelsTheTelegramDownload`, `unavailable…` (3 tests), `releaseCancelsTheDownloadAndTheCacheEvictsOldStreams`, `LibraryMediaSourceResolverTest` (3) |

### Measured numbers (full suite, this machine)

Wall-clock times through the whole stack. The simulated network (bandwidth + latency) dominates
them. Media time runs on Media3's fake clock. Values from the last full run; repeated runs
varied by roughly ±30%.

| Metric | Network | Value |
|---|---|---|
| Startup (prepare → READY), 360p MP4 | 4 MB/s, 40 ms per part | 120–150 ms |
| Startup by format | 2 MB/s, 30 ms | 360p 117 ms · 480p MKV 182 ms · 720p moov-at-end 264 ms · 1080p 199 ms |
| Startup, 85-min 148 MB MP4 | 8 MB/s, 40 ms | ~400 ms |
| Startup, slow network, 720p moov-at-end | 128 KB/s (~1 Mbit/s), 250 ms | ~2.9 s (264 KB must arrive incl. the moov at the end) |
| Seek 00:00 → 00:05 | 8 MB/s, 40 ms | 30–80 ms |
| **Seek 00:05 → 01:20:00** | 8 MB/s, 40 ms | **~130 ms; 0.6% → 0.8% of the file downloaded in total** |
| Repeated seeks (8 random, settled) | 8 MB/s, 30 ms | median ~160 ms, max ~290 ms; after a 5-seek scrub ~110 ms; ≤ 4% of the file downloaded |
| Engine: first byte after a 188 MB jump | 20 MB/s, 20 ms | ~28 ms; 0.44% of 200 MB downloaded |
| Engine: random seek first byte | 40 MB/s, 10 ms | median ~14 ms |
| Buffering, 1.5 s outage mid-play | 96 KB/s | 1 rebuffer, playback continues, no error |
| Stall detection (network down) | — | 0.76–0.79 s with a 0.7 s timeout (default timeout is 20 s) |
| Recovery after outage + `retry()` | 64 KB/s | ~3.3 s to finish the rest of the clip |
| Cancel a blocked read (thread interrupt) | — | < 2 ms |
| Paused reader | unlimited | download stops at the window (4.0 MB with a 4 MB window) |
| Memory | — | Media3 buffer capped at 32 MB by config. The test JVM heap peaked at 125–215 MB, but that is Robolectric + the test harness, not a device figure |

The test player (`PlayerActivity`, ▶ in the Library debug tab) shows the same metrics live on a
device: startup and first-frame time, rebuffer count and time, every seek latency, retries,
bytes served and downloaded with request count, time spent waiting for data, and peak Java and
native heap. Buttons: play/pause, −10 s, +30 s, → 01:20:00, 5 random seeks, retry, fast-seek
toggle, stop.

## 10. Limitations

- **No device or emulator run.** This environment has no Android device and no KVM. Every
  number above comes from Robolectric + simulated Telegram. Real TDLib timings (part sizes up
  to 512 KB–1 MB, server latency, DC switching), hardware decoders and real memory use must be
  measured with the test player on a phone.
- **Robolectric gaps:**
  - Audio *rendering* is disabled in the playback tests, because Robolectric's AudioTrack never
    drains on long content. Audio samples are still downloaded and demuxed, so the byte traffic
    is the same.
  - First-frame time is not measured in tests (no surface).
- **Progress granularity.** TDLib reports progress per part. On very slow links one part can
  take seconds, so `stallTimeoutMs` must stay well above the time for one part. The 20 s
  default allows ~1 MB parts at 400 kbit/s.
- **One download per file.** Telegram keeps one download position per file. Two readers of the
  same file at distant positions (e.g. a future thumbnail extractor while playing) would pull
  the download back and forth. Playback has one loader at a time, so this does not arise today.
- **Seeking needs an index.**
  - Files without an index (MKV without Cues, some AVI/TS) are not seekable or seek slowly; the
    format decides, not the network.
  - Moov-at-end MP4s need one extra jump at startup, as tested.
- **Formats.** Formats and codecs the device cannot decode (e.g. HEVC 10-bit or AV1 on older
  phones, DTS/TrueHD audio) report `UNSUPPORTED_FORMAT`/`DECODER`. FFmpeg extension decoders
  are not bundled (licensing and size; a later phase).
- **Session-local file ids.** Ids are re-resolved at every play. Offline, only files TDLib still
  knows under the indexed id can be played.
- **Cache scope.** `StreamCache` tracks only files streamed since the app started. Older partial
  files remain until TDLib or Android clears the cache directory.
