# YourStream for Windows

The `desktop` module is YourStream as a Windows app (the same code also runs on Linux and macOS,
but only a Windows installer is built): your Telegram library, Stremio / Nuvio add-ons (Discover
and streams), a Nuvio-style player, the manga style, and in-app updates.

## What it does

| Part | How |
|---|---|
| Sign in | Telegram's own login: phone number and code, two-step password, or a QR code scanned with Telegram on a phone. The session is stored by TDLib in the data folder. |
| Chats | Pick the channels, groups or chats that hold your videos; they are indexed in the background while the app is open, and checked for new posts every 15 minutes. |
| Library | Movies with their versions (4K first), series with seasons and episodes, search, a Movies/Series filter. Posters from Cinemeta, or TMDB with your key (Settings). |
| Add-ons | Stremio / Nuvio add-ons, like on Android (the shared catalog:stremio and catalog:core code): install by link (https://…/manifest.json, stremio:// works too; plain http only on the local network), order, remove. Cinemeta is installed at first. |
| Discover | The add-ons' catalogs as poster grids (Movies / Series, pick a catalog, Load more) and search across add-ons. A title's page shows its details, seasons and episodes, your Telegram files for it first, then every add-on's streams as they come. HTTP streams play in the player (with the stream's User-Agent / Referer); torrents are listed but not played on Windows yet. |
| Style | Manga (default, like Android: Bangers lettering, inked panels, screentones, speed lines) or Classic; dark or light (Settings → Appearance). |
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
  the QR code to a phone number.

What the tests do **not** cover, and needs a Windows PC with a real Telegram account: signing in
for real, indexing real chats, the video window (VLC drawing into the app window, fullscreen,
keyboard focus), playing real large files from Telegram, and the installer itself (install,
shortcuts, upgrade, uninstall).
