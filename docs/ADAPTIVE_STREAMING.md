# Adaptive streaming (Phase 8)

Phase 7 streams Telegram files with a fixed configuration. Phase 8 makes buffering,
seeking and downloading react to the network, the media, the device and the user's behaviour,
for unreliable mobile networks.

```
                 ┌──────────────────────── :playback:adaptive (pure Kotlin) ─────────────────────────┐
 player sample ─▶│ AdaptiveStreaming ─▶ NetworkMonitor.tick ─▶ BufferManager.update ─▶ BufferPolicy  │
 (2×/s)          │        │                                                │                        │
                 │        │            ┌───────────────────────────────────┼──────────────────┐     │
                 │        ▼            ▼                                   ▼                  ▼     │
                 │  PrefetchManager   AdaptiveLoadControl (RAM buffer)   streamer.setReadAhead   risk │
                 │        │           (:playback:media3)                 (disk window)        HIGH:  │
                 │        ▼                                                                   pause  │
 user seeks ────▶│  SeekManager ─ coalesce ─▶ player.seekTo ; pause prefetch ; streamer.focus  prefetch│
                 │        │                                                                         │
                 │        ▼                                                                         │
                 │  DownloadGovernor (every playback/prefetch download; limits, preemption, bytes)   │
                 └────────┬──────────────────────────────────────────────────────────────────────────┘
                          ▼
 TelegramFileStreamer ── transfers / waits / stalls ──▶ NetworkMonitor (throughput, latency)
                          ▼                              ▲ AndroidConnectivity (Wi-Fi / mobile / metered)
                 TelegramFileRepository → TDLib          └ Telegram connection state
```

| Component | Where | Job |
|---|---|---|
| `NetworkMonitor` / `DefaultNetworkMonitor`, `BandwidthEstimator` | `:playback:adaptive` | connection type, metered, measured throughput and latency, quality, outages, network-switch events |
| `BufferManager` (+ `BufferTrend`, `DeviceProfile`) | `:playback:adaptive` | the buffer policy: RAM thresholds, disk read-ahead, prefetch permission, rebuffer risk, fast-seek preference |
| `SeekManager` | `:playback:adaptive` | coalescing, scrubbing, cancelling transfers in the way |
| `PrefetchManager` | `:playback:adaptive` | next-episode head + tail, only when there is bandwidth to spare |
| `DownloadGovernor` | `:playback:adaptive` | concurrency limits, playback-first preemption, prefetch budget, byte accounting |
| `AdaptiveStreaming` | `:playback:adaptive` | per-player coordinator |
| `AdaptiveLoadControl`, `AndroidConnectivity`, `deviceProfile()`, `NextEpisodeProvider`, `AdaptivePlayback` | `:playback:media3` | Media3 / Android glue; `TelegramPlaybackController(adaptive = …)` |
| Streamer changes | `:playback:stream` | dynamic read-ahead, ramping window, transfer observers, offline-aware stalls, distinct-bytes accounting, `refreshDownloads`, `focus` |

Configuration (outside source control, `secrets.properties` / env): `playback.adaptive`,
`playback.maxConcurrentDownloads` (1–4), `playback.prefetchOnMobileData`. Everything else is in
`TransferLimits`, `BufferConfig`, `PrefetchConfig`, `StreamingConfig`, `SeekConfig` (code defaults,
injectable through `PlaybackSettings`).

## 1. Network awareness: `NetworkMonitor`

**Signals:**
- OS default network (`ConnectivityManager`): type, metered, bandwidth hint.
- Telegram's connection state.
- Measurements from the streamer.

**Throughput.** Measured from Telegram progress: bytes that arrived between two file updates while
a download was running. Time spent offline is excluded. `BandwidthEstimator` keeps two EWMAs
weighted by sample duration: fast (half-life 2 s) and slow (8 s), both zero-bias corrected. The
estimate is **min(fast, slow)**: it believes drops within ~2 s and ignores short spikes. Tiny
samples are merged, because TDLib reports per part.

**Latency.** EWMA of request → first byte at a position.

**Quality:**

| Quality | Condition |
|---|---|
| OFFLINE | OS has no network, or Telegram has been disconnected > 2 s (grace avoids flapping on reconnects) |
| POOR | 2 stalls within 30 s, or < 64 KB/s (≈ 0.5 Mbit/s) |
| FAIR | < 512 KB/s |
| GOOD | otherwise |
| UNKNOWN | no samples yet; the OS hint, halved, is used |

**Events:**
- `Switched(Wi-Fi → mobile)`: the fast EWMA is reset and the slow one decays to 25%, because the
  old network's speed means nothing now. Current downloads are re-sent.
- `Lost`: prefetch paused; readers wait instead of failing.
- `Restored(offlineMs)`: downloads re-sent, prefetch resumed.

**Temporary disconnection.** While the monitor says OFFLINE, the streamer does not count waiting
time towards its 20 s stall timeout, for up to 120 s (`maxOfflineWaitMs`). The player shows
buffering and continues by itself when the connection is back. No error, no retry cascade.

## 2. Adaptive buffering: `BufferManager`

**Inputs (every sample):**
- network quality and throughput;
- media bitrate (stream format, or size/duration);
- device profile (memory class, low-RAM flag, cores) and dropped-frame rate;
- buffered-ahead level, its trend, and whether the player is loading;
- playback phase (starting, playing, paused, buffering, seeking).

**Output:** a `BufferPolicy`. The `AdaptiveLoadControl` re-reads it on every Media3 decision, and
the streamer's download window follows it.

**Algorithm:**

1. **Tier by quality**, made stricter by headroom h = throughput / bitrate (h < 1.2 → poor tier;
   h < 2 → at most fair). Hysteresis: a worse network applies at once; a better one only after
   holding for 10 s.

   | Tier | RAM buffer min–max | Disk read-ahead |
   |---|---|---|
   | GOOD | 15–50 s | 120 s |
   | FAIR | 25–90 s | 180 s |
   | POOR | 40–150 s | 300 s |
   | UNKNOWN | 20–60 s | 120 s |

2. **Memory bound.** The RAM buffer never exceeds the device budget: 12 MB on low-RAM devices,
   24 / 48 / 64 MB by memory class. maxBuffer = budget / bitrate. When memory caps the RAM
   buffer, the disk window (Telegram cache) still holds the read-ahead, so protection moves to
   disk instead of disappearing.
3. **Start / resume thresholds.**
   - When the network is faster than the video, start quickly: 1 s for h ≥ 3, 2 s for h ≥ 1.5,
     4 s for h ≥ 1.1, 6 s for h ≥ 1.
   - When it is slower, the buffer drains at (1 − h) per second of playback, so a buffer of
     B = (1 − h) · T buys T seconds without a stall. The start buffer targets T = 5 min
     (6–30 s). After a stall the resume buffer targets T = 2 min × 1.5^(recent stalls), capped at
     20 s.
   - Result: few longer stalls instead of many short ones.
4. **Exploration.** Two or more seeks within 60 s mean the user is searching. Until 30 s without
   a seek, the RAM buffer is capped at 15 s and the disk window at the same. This avoids filling
   a 50 s buffer at every position the user is about to leave.
5. **Rebuffer prevention.** `BufferTrend` is a least-squares slope of buffered-ahead over 8 s,
   measured **only while playing and loading**. A full buffer drains at playback speed by design
   when the load control stops fetching, and must not raise an alarm.
   - **ELEVATED** risk: the buffer falls faster than 0.5 s per second, or faster than 0.2 s/s
     with under 60 s until empty.
   - **HIGH** risk: under 8 s until empty, or below the resume threshold while falling.
   - Response to either: minBuffer = maxBuffer (load continuously), disk window doubled,
     prefetch refused. On HIGH, running prefetches are cancelled and the current download is
     re-sent, unchanged.
   - A raised risk is held for 10 s, because the trend is noisy.
6. **Bandwidth protection on metered networks.** Read-ahead is capped at 90 s (30 s while
   paused), unless there is risk. It is never below 1.2× the RAM buffer, so loading is not
   starved.
7. **Prefetch permission.** Allowed on GOOD, or FAIR with h ≥ 2. Never at risk, offline, on
   metered networks (unless configured), or while not playing or paused.
8. **Device performance.** More than 3 dropped frames/s, or a low-RAM device with ≤ 4 cores,
   switches seeks to key frames (`CLOSEST_SYNC`): less decoding per seek. More buffer cannot fix
   a slow decoder.

## 3. Smart seeking: `SeekManager` + streamer

- **Immediately request the target.** The first seek of a burst is applied at once (leading
  edge). Media3 opens the data source at the target sample within milliseconds, and the
  streamer re-points the Telegram download there (Phase 7).
- **Cancel unnecessary transfers.** Before applying a seek:
  - prefetching is paused and its downloads are cancelled;
  - `streamer.focus(file)` cancels downloads of other files nobody is reading;
  - the old window of the current file is abandoned by Telegram's re-pointed download.
- **Avoid waste.**
  - Seeks within 150 ms of an applied seek are merged; only the last target is applied when the
    window ends (trailing edge). ±10 s taps and keyboard repeats cost one download, not eight.
  - Scrubbing (`beginScrub` / `endScrub`) applies key-frame previews at most every 300 ms, then
    one exact seek.
  - Each jump starts a **4 MB window** that doubles on every extension up to the read-ahead. A
    position the user leaves after a glance costs ≤ 4 MB, while continued playback reaches the
    full window within a few extensions.
- **Extensions never restart transfers.** Extending a running download keeps its offset and only
  raises the limit; refreshes re-send the identical request. A new offset would make Telegram
  drop the part in flight, which the benchmarks showed costs ≈ 2 s per extension on a 2 Mbit/s
  link.
- Prefetch resumes when the player is ready at the new position.

## 4. Next-episode prefetch: `PrefetchManager`

**Trigger:** all of the following hold:
- the episode is near its end (≤ 120 s left or ≥ 90% played);
- the policy allows prefetch (§2.7);
- a next episode is known (`EpisodeRepository.nextEpisode`, resolved like any playback: fresh
  file id, availability check);
- it is not on the device already.

**What it fetches**, at priority 4, one at a time, through the governor:
- the **head**: 20 s of playback, 2–12 MB; and
- the **tail**: 1 MB, where moov-at-end MP4s and MKV cues sit, which the player reads before it
  can start.

**Never the whole episode.** It pauses (and cancels) whenever the policy withdraws permission
(seek, risk, metered, offline). It resumes with what is missing. The session budget is 32 MB.

**Trade-off, measured:**
- Next-episode startup: 300 ms → 120 ms.
- If the user stops instead, the head + tail are wasted: ~6.5 MB at 2 Mbit/s.

## 5. Bandwidth protection: `DownloadGovernor`

Every download from the streamer and the prefetcher passes through the governor. That makes
unlimited concurrency impossible.

| Rule | Default |
|---|---|
| Concurrent downloads (all classes) | 2 (`playback.maxConcurrentDownloads`, 1–4) |
| Concurrent prefetches | 1 (0 when the limit is 1) |
| Prefetch bytes per session | 32 MB |
| Class by priority | ≥ 24 playback (streamer uses 32), ≤ 8 prefetch, else other |

**Behaviour:**
- Playback is always admitted. It preempts the lowest-priority (then oldest) download when the
  limit is reached.
- A prefetch never preempts. It is refused when paused, over its own limit, over budget, or when
  no slot is free.
- Re-requests of an admitted file take no extra slot.
- Downloads leave the active set when Telegram reports them finished or stopped.
- Bytes are accounted per class.

## 6. Benchmarks

`./gradlew :playback:adaptive:benchmark`. The report is written to
`playback/adaptive/build/reports/benchmarks/streaming-benchmark.md`.

**Harness.**
- `VirtualPlayer` consumes a constant-bitrate file in real time with ExoPlayer's rules: load
  hysteresis, start and resume thresholds, load-error backoff (1/2/4/8/8 s, then an error
  shown).
- It reads through the real `TelegramFileStreamer`. Adaptive runs also use the real governor,
  monitor, buffer manager, seek manager and prefetch.
- Telegram is `SimulatedTelegramFiles`:
  - 512 KB parts, latency per part, bandwidth traces, outages;
  - a new offset drops the part in flight, as in TDLib.
- Everything runs 20× faster than the media time it models. Bandwidth ×20, latency /20, and all
  clocks are scaled, so the components see unscaled values.

**Media:** a 2 Mbit/s video in 24-minute episodes.

**Configs:**
- **Baseline** = Phase 7: fixed 15–50 s buffer, 1 s start, 2.5 s resume, full 90 s window from
  the first request, no offline awareness, no coalescing, no prefetch, no limits.
- **Adaptive** = Phase 8.

**Metric definitions:**
- *waste* = downloaded − distinct bytes played. It includes read-ahead left when the session
  ends.
- *Peak RAM buffer* = media held in the player buffer.
- *Peak JVM heap Δ* is a noisy whole-process figure, shown for completeness only.

| Scenario | Config | Startup ms | Seek ms (median / p90, n) | Rebuffers (per h) | Stall time ms | Load errors / shown | Downloaded MB | Played MB | Waste MB (%) | Peak RAM buffer MB | Peak JVM heap Δ MB | Peak concurrent downloads | Next-episode startup ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Mobile, fluctuating 0.5-4 Mbit/s | adaptive | 1,260 | – | 1 (13.3) | 28,540 | 0 / 0 | 72.0 | 65.9 | 6.1 (8%) | 12.2 | 4 | 1 | – |
| Mobile, fluctuating 0.5-4 Mbit/s | baseline | 1,260 | – | 3 (39.4) | 24,180 | 0 / 0 | 71.0 | 67.0 | 4.0 (6%) | 9.6 | 6 | 1 | – |
| Next episode (watch last 150 s, continue) | adaptive | 620 | – | 0 (0.0) | 0 | 0 / 0 | 61.6 | 43.9 | 17.7 (29%) | 12.3 | 52 | 2 | 120 |
| Next episode (watch last 150 s, continue) | baseline | 620 | – | 0 (0.0) | 0 | 0 / 0 | 70.1 | 43.9 | 26.2 (37%) | 12.2 | 51 | 1 | 300 |
| Next episode prefetched, user stops | adaptive | 620 | – | 0 (0.0) | 0 | 0 / 0 | 43.1 | 36.6 | 6.5 (15%) | 12.2 | 48 | 2 | – |
| Next episode prefetched, user stops | baseline | 620 | – | 0 (0.0) | 0 | 0 / 0 | 37.1 | 36.6 | 0.4 (1%) | 12.3 | 47 | 1 | – |
| Poor mobile, 2.2 Mbit/s for 2 Mbit/s video | adaptive | 2,180 | – | 2 (32.7) | 17,280 | 0 / 0 | 56.0 | 53.8 | 2.2 (4%) | 2.5 | 4 | 1 | – |
| Poor mobile, 2.2 Mbit/s for 2 Mbit/s video | baseline | 2,160 | – | 5 (79.2) | 8,740 | 0 / 0 | 56.0 | 55.5 | 0.5 (1%) | 1.0 | 5 | 1 | – |
| Poor mobile, low-RAM device | adaptive (low-RAM device) | 2,580 | – | 2 (32.7) | 17,340 | 0 / 0 | 55.5 | 53.8 | 1.7 (3%) | 2.5 | 40 | 1 | – |
| Seek-heavy (5 bursts of 8 rapid seeks + 5 single seeks) | adaptive | 520 | 580 / 1,020 (11) | 0 (0.0) | 0 | 0 / 0 | 111.5 | 39.3 | 72.2 (65%) | 12.2 | 51 | 1 | – |
| Seek-heavy (5 bursts of 8 rapid seeks + 5 single seeks) | baseline | 540 | 560 / 1,040 (11) | 0 (0.0) | 0 | 0 / 0 | 174.0 | 39.6 | 134.4 (77%) | 12.3 | 52 | 1 | – |
| Wi-Fi -> 100 s outage -> mobile | adaptive | 420 | – | 1 (14.9) | 57,620 | 0 / 0 | 86.0 | 59.0 | 27.0 (31%) | 22.0 | 56 | 1 | – |
| Wi-Fi -> 100 s outage -> mobile | baseline | 400 | – | 1 (14.9) | 57,740 | 4 / 0 | 81.0 | 59.0 | 22.0 (27%) | 12.3 | 55 | 1 | – |
| Wi-Fi, stable 20 Mbit/s | adaptive | 320 | – | 0 (0.0) | 0 | 0 / 0 | 73.0 | 58.5 | 14.5 (20%) | 12.3 | 49 | 1 | – |
| Wi-Fi, stable 20 Mbit/s | baseline | 320 | – | 0 (0.0) | 0 | 0 / 0 | 77.0 | 58.5 | 18.5 (24%) | 12.3 | 51 | 1 | – |

**Reading the numbers:**

- **Rebuffer frequency.**
  - Poor network: 79 → 33 per hour. Fluctuating mobile: 39 → 13 per hour.
  - Total stall time is *higher* (17 s vs 9 s; 29 s vs 24 s). This is the deliberate trade-off
    (§7): fewer, longer interruptions. Over a whole episode the total stall time on a network
    slower than the video is fixed by the deficit; what the policy decides is how it is cut up.
- **Bandwidth waste.**
  - Seek-heavy: −46% downloaded (174 → 112 MB) for ~+20 ms median seek latency (the coalescing
    window).
  - Stable Wi-Fi: −4 MB.
  - Next episode: −9 MB, because the current episode's tail window is smaller.
  - Prefetch costs +6 MB when the user does not continue.
- **Startup latency.**
  - Next episode: 300 → 120 ms (prefetch).
  - Unknown network at first start: equal, since both start at 1–2 s of buffer.
- **Outages.**
  - A 100 s outage at the start of playback stalls both configs equally; nothing can download.
  - The baseline produced 4 stall-timeout load errors (each a retry with backoff; with longer
    outages, errors shown to the user).
  - Adaptive produced none: it knows it is offline and waits.
- **Memory.**
  - RAM buffer peaks at the device budget's equivalent of ≤ 50–90 s.
  - On a low-RAM device the budget is 12 MB. The disk window takes the rest.
  - Adaptive uses more RAM on FAIR networks (22 MB vs 12 MB after the switch to mobile) by
    design.
- **Concurrency.** Never above the limit of 2 (playback + prefetch).

Run-to-run variation is about ±10–20% on times; byte counts are stable.

## 7. Trade-offs

| Decision | Gain | Cost |
|---|---|---|
| Bigger start/resume buffers when the network is slower than the video | 2–3× fewer stalls | Longer individual waits; more total stall time inside a short session |
| min(fast, slow) bandwidth estimate | Reacts to drops in ~2 s, ignores spikes | Underestimates on bursty links → slightly more buffering than needed |
| Hysteresis (better network must hold 10 s; raised risk held 10 s) | No oscillation of windows and thresholds | Relaxes slower after recovery |
| Generous disk read-ahead on unmetered networks (120–300 s) | Outage cushion (the player refills RAM from disk) | More bytes left unwatched when the user stops (Wi-Fi only) |
| Metered: ≤ 90 s read-ahead, 30 s when paused; no prefetch by default | Saves mobile data | Less protection against dips on mobile |
| Seek coalescing (150 ms trailing window) | Intermediate positions never download | The last seek of a burst waits up to 150 ms |
| Small window after a jump, doubling | Scrubbing wastes ≤ 4 MB per position | A slow network fills the full window a few round trips later |
| Exploration mode (cap 15 s while seeking around) | Less waste while searching | If the network drops right after the user settles, there is less buffer |
| Head + tail prefetch only | Instant next start, bounded cost (~6 MB) | Wasted when the user does not continue; 0 benefit on metered by default |
| Hard concurrency limit (2) | Playback never competes with more than one transfer | A third consumer (e.g. a second player) preempts or waits |
| Offline: wait up to 120 s instead of failing | No error cascade; resumes by itself | A permanent outage takes 2 min to surface as an error |

## 8. Tests

**Unit tests** (`:playback:adaptive`, 15):
- estimator: convergence, drops vs spikes, network switch;
- monitor: classification, switch / lost / restored events, grace period, stall-induced POOR;
- buffer manager: tiers, headroom, memory cap, drain detection, no false alarm for a full
  buffer, metered and paused limits, stall growth, hysteresis;
- seek manager: leading and trailing edges, bursts, scrubbing;
- governor: hard limit, preemption, refusal, pause;
- prefetch: head + tail only, waits when forbidden.

**Robolectric with real ExoPlayer** (`AdaptivePlaybackTest`, 3):
- the adaptive load control plays, and the disk window follows the policy;
- 5 rapid seeks reach the player as ≤ 2;
- the next episode's head is prefetched near the end within the download limit.

The Phase 7 suites still pass with the streamer changes.

## 9. Limitations

- **Simulated network.** The benchmarks model TDLib's download behaviour and a CBR video, not a
  real Telegram data centre or radio. Real devices add DC routing, TCP slow start, radio state
  changes and VBR content. The test player shows the same metrics live, including network
  quality, policy and its reason, risk, governor and prefetch state, so this can be verified on
  a phone.
- **Robolectric ExoPlayer.** Media time runs on a fake clock, so rebuffer behaviour can only be
  benchmarked with the virtual player, not with Robolectric ExoPlayer.
- **One download per file.** The governor limits *files*. A file has a single Telegram download
  position, so two readers of one file still share it.
- **Next-episode knowledge.** It comes from the library (season/episode numbering). Movies, or
  episodes whose next part is missing or unavailable, are not prefetched.
- **Bitrate.** Taken from the stream format or size/duration. Strongly VBR content makes
  headroom estimates rougher.
- **Exploration mode** may mislabel deliberate repeated jumps (e.g. replaying a scene) as
  searching. The effect is a smaller buffer for 30 s.
