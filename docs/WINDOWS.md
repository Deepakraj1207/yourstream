# YourStream for Windows

The `desktop` module is YourStream as a Windows app (the same code also runs on Linux and macOS,
but only a Windows installer is built): your Telegram library, Stremio / Nuvio add-ons (catalog
rows and streams), Nuvio-style screens and player, the manga style, and in-app updates.

## What it does

| Part | How |
|---|---|
| Sign in | Telegram's own login: phone number and code, two-step password, or a QR code scanned with Telegram on a phone. The session is stored by TDLib in the data folder. |
| Layout | Like the Android app's Nuvio-style screens, for a big window: a floating glass sidebar (Home, Search, Library, Settings), full-bleed artwork that fades into a black page, rows of rounded posters that lift on hover, white pill buttons. Esc goes back a page. |
| Home | A hero carousel across the top (the first add-on catalog's titles with artwork, or your latest Telegram titles; the next slide every 8 s, arrows and dots), then rows: Continue watching, Recently added · Telegram, and each add-on catalog ("Popular · Movies") with arrows on hover and See all. Add-on titles you have on Telegram carry a "Telegram" badge, and the hero offers Play for them. |
| Search | One big field: your Telegram titles as you type, then every add-on's results, one row per add-on and kind ("Cinemeta · Movies"). |
| Title pages | The artwork across the window with the title, year, runtime, rating, genres, overview and credits over it; Play (from Telegram when you have it, else the first playable stream) and Sources; for a series, season pills and episode cards with stills; then your Telegram files and each add-on's streams. |
| Chats | Settings → Telegram chats: pick the channels, groups or chats that hold your videos; they are indexed in the background while the app is open, and checked for new posts every 15 minutes. |
| Library | Movies with their versions (4K first), series with seasons and episodes, search, a Movies/Series filter. Posters from Cinemeta, or TMDB with your key (Settings → Posters). |
| Add-ons | Settings → Add-ons: Stremio / Nuvio add-ons, like on Android (the shared catalog:stremio and catalog:core code): install by link (https://…/manifest.json, stremio:// works too; plain http only on the local network), order, remove. Cinemeta is installed at first. HTTP streams play in the player (with the stream's User-Agent / Referer); torrents are listed but not played on Windows yet. A row's See all opens the whole catalog as a grid (Movies / Series, another catalog, search, Load more). |
| Settings | Sections in a list on the left: Appearance, Telegram chats, Add-ons, Player, Posters, Updates, Storage & cache, Account. |
| Storage & cache | What each cache uses (Telegram video parts, poster images, downloaded updates), Clear for each, and **Clear cache** for all of them. Telegram's files are deleted by TDLib itself; poster matches, the Telegram session, the index, settings and watch positions are kept. An update that is downloading is not cleared. |
| Style | Classic (black, white text and buttons) or Manga (default, like Android: Bangers lettering, inked panels, screentones, speed lines); dark or light (Settings → Appearance). |
| Updates | The latest GitHub release is checked at start (and in Settings → Updates). A newer installer is downloaded, checked against `SHA256SUMS-windows.txt`, installed over the app (msiexec), and the app restarts. |
| Playback | Streams while downloading: the app serves the Telegram file to VLC over `127.0.0.1` with byte ranges, so seeking anywhere works before the file is downloaded; the start and end of a title's file are fetched while its page is open, so Play starts at once. Nuvio-style player: title and source at the bottom left, thin seek bar, round play button, aspect / speed / subtitles / audio / sources / info panels, volume and time; controls hide while playing. Audio and subtitle tracks named by language and format (the pick is remembered); "Resumed from … · Start over". Shortcuts (handled by the window, so they always work): Space/K play-pause, ←/→ or J/L 10 s (Shift 60 s), Home/End, 0–9 jump, ↑/↓ or wheel volume, M mute, A next audio, S next subtitles, V subtitles on/off, [ ] = speed, Z aspect, N next episode, I info, F/F11 or double-click fullscreen, Esc, ?/H the list. |

Not yet: movies split into pieces (`.mkv.001`, `.002`, …) only show their first piece and are not
playable as one movie; torrent streams; files on the PC; downloads for offline viewing; progress
sync with the phone.

## Install

1. Download `YourStream-…-windows-x64.msi` from the release (when a release includes it).
2. Run it. It installs for the current user (no administrator rights needed) and adds a Start
   menu entry and a desktop shortcut. Windows SmartScreen may warn because the installer is not
   code-signed: *More info → Run anyway*.
3. Updates: install the newer `.msi` over the old one (same upgrade code, so it replaces it).

VLC: the installer carries VLC's player libraries (from VideoLAN's 64-bit build, checked against
VideoLAN's SHA-256). If a build was made without them, the app uses an installed VLC (64-bit),
and asks for it if there is none.

## Where things are kept

`%LOCALAPPDATA%\YourStream` (not roaming):

| Path | What |
|---|---|
| `telegram\db` | TDLib's database: your Telegram session |
| `telegram\files` | Downloaded video parts (streaming cache, trimmed to 2 GB of finished files) |
| `index.jsonl` | The index of your chats' video files |
| `settings.json` | Theme, TMDB key, watch positions |
| `cache\posters` | Poster matches and images |
| `logs` | The app's log and TDLib's (no phone numbers, codes or passwords) |

Signing out (Settings → Account) ends the session with Telegram.

## How it is built

* **Shared code, unchanged.** The plain-Kotlin modules run as they are: `telegram:core` (sign-in,
  chats, files), `media:filename`/`media:parser`, `metadata:*` (matching, TMDB, Cinemeta),
  `playback:stream` (streaming Telegram files while downloading). Two plain-Kotlin parts of
  Android library modules are compiled into the desktop app from their source files at build time
  (no copies in git): the TDLib client (`telegram/tdlib`: `TdlibTelegramClient`, `TdMappers`) and
  the indexing engine with its store contract (`media/indexer`: `engine/`, `store/IndexStore.kt`).
* **TDLib** comes from `tdl-coroutines`' JVM build, which carries TDLib for Windows (x64 and
  arm64), macOS and Linux.
* **Player.** One libvlc instance for the app, started in the background after sign-in (it
  loads the plugins once). Each playback is a `VideoPlayerController`: every VLC call runs on its
  own thread, never the UI thread, and closing returns at once. Frames go through a callback
  surface into three Skia bitmaps (one copy per frame, straight from VLC's buffer, no allocation),
  drawn by Compose and cropped to the picture (VLC's buffers include block padding). The stream
  server lets the newest request for a file win (a seek ends the old read) and aborts a file's
  reads when its playback ends.
* **Replaced for the desktop:** Room → `DesktopIndexStore` (an append-only JSON-lines journal:
  every write is one flushed append, media before their cursor, a cut-off last line is skipped,
  and the journal is rewritten when it grows far beyond the live records); WorkManager →
  `IndexRunner` (coroutines); Media3 → VLC through vlcj, fed by `StreamServer`; the Android
  screens → Compose for Desktop screens in `desktop/src/main/kotlin/…/ui`.
* **Installer size.** Two jars are slimmed at build time (`SlimJar` in `desktop/build.gradle.kts`):
  TDLib keeps only the build machine's platform (Windows x64 for the installer; the jar carries six),
  and the Material icons keep only the icons the source names (`SlimJarsTest` checks both). The VLC
  copy leaves out plugins a player inside the app does not use (VLC's own window, scripting,
  visualisers, network browsing, stream output and encoders); see
  `desktop/scripts/fetch-vlc-windows.sh`.
* **Credentials** come from the same place as the Android app's (`TELEGRAM_API_ID` /
  `TELEGRAM_API_HASH`, or `secrets.properties`) into a resource read at start; they are never
  printed or logged.

## Build and run

```bash
./gradlew :desktop:run          # run from source (needs VLC installed to play videos)
./gradlew :desktop:test         # the desktop tests
./gradlew :desktop:packageMsi   # the installer (on Windows only)
```

To ship VLC with a locally built installer, run `desktop/scripts/fetch-vlc-windows.sh` (Git Bash)
first; it fills `desktop/appResources/windows/vlc` (not committed).

## Tests

`./gradlew :desktop:test` (CI runs it on Linux in the main job and on Windows in the `windows`
job, which also builds the installer):

* `TdlibNativeTest`: TDLib's native library loads and the shared client starts it up to the
  phone-number step (no network or account needed).
* `DesktopIndexStoreTest`: index survives restarts, a cut-off last line, removing a chat,
  compaction.
* `LibraryTest`: the shared indexing engine indexing a (fake) chat into the desktop store, then
  movies with versions, series with seasons, split files, remakes, accent-insensitive search.
* `StreamServerTest`: real HTTP on 127.0.0.1 in front of the real streamer and a simulated TDLib
  download: whole file, byte ranges for seeks, suffix ranges, HEAD, 416, a wrong token.
* `VlcPlaybackTest`: real VLC (vlcj) plays a real MKV through the stream server, and seeks with a
  new byte range into data not yet downloaded. Skipped where VLC is not available.
* `ScreensTest`: the library, a series page, a movie page and sign-in rendered with sample data;
  clicks open titles, play episodes with the rest queued, resume the right version, switch from
  the QR code to a phone number; the manga style.
* `VideoPlayerControllerTest`, `TrackLabelsTest`: real VLC draws frames into the app's buffers; a
  file with Tamil AC3 5.1, English AAC and English subtitles gets named tracks and the preferred
  language at start; closing never waits for VLC.
* `PlayerScreenTest`: the player's overlay, the audio panel, and the window-level shortcuts.
* `AddonsTest`: a small add-on served on 127.0.0.1: install, catalogs, details, streams (with the
  stream's User-Agent), a catalog grid and an add-on title page, and an add-on stream played by VLC.
* `HomeAndSearchTest`: the window's shell (sidebar) with a local add-on serving generated artwork
  and a sample Telegram library: Home's hero and rows (Continue watching, Recently added, the
  catalogs, See all, the Telegram badge and Play for titles you have), Search across Telegram and
  add-ons, add-on movie and series pages (streams, episodes), and Settings' sections, in the classic
  and manga styles. Screenshots go to `desktop/build/screenshots`.
* `DesktopUpdatesTest`: a newer release found, the installer downloaded and checked against
  `SHA256SUMS-windows.txt` and installed; the same or an older version is up to date; a damaged
  download is deleted and nothing is installed.
* `DesktopStorageTest`: each cache measured; Clear cache empties them through their owners and
  keeps the session, index, settings and poster matches; one cache at a time; empty ones skipped.
* `SlimJarsTest`: the slimmed TDLib and icon jars keep what the app needs.
* `StreamingBenchmark` (opt-in, `-Dbench=true`): time to first picture and seeks over a
  simulated slow Telegram download.

What the tests do **not** cover, and needs a Windows PC with a real Telegram account: signing in
for real, indexing real chats, the video window (VLC drawing into the app window, fullscreen,
keyboard focus), playing real large files from Telegram, and the installer itself (install,
shortcuts, upgrade, uninstall).
