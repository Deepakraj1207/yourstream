# YourStream

An open-source Android app that turns the videos in your own Telegram chats, and on your
device, into a private media library: movies and series with posters, episodes and resume,
played straight from Telegram without downloading first.

## What it does

* **Your Telegram videos as a library.** Sign in with your own Telegram account (phone number
  and code, or a QR code), choose the channels, groups or chats that hold your videos, and
  YourStream indexes them in the background: movies, series → seasons → episodes, several
  versions of the same title, duplicates recognised.
* **Files on this device.** Add folders (no permission needed: only the folder you pick) or all
  videos on the device; they join the same library.
* **Posters and details** from TMDB or Cinemeta, matched from the title and year in the file
  name, with a review screen for unsure matches.
* **Playback** with Media3: streams while downloading, seeks anywhere, picks audio and subtitle
  languages by preference, FFmpeg audio for formats the device lacks, background playback with
  lock-screen controls, picture-in-picture, next episode.
* **Watch tracking:** continue watching, next episode, recently added, favorites, statistics, all
  computed on the device. Optional end-to-end encrypted progress sync through your own Saved
  Messages.
* **Offline:** download a version to watch without a connection.
* **Search** that works offline: fuzzy, transliteration-aware, with plain-language questions
  ("Telugu movies I haven't watched").
* **Optional extras:** catalog add-ons for discovery, web streams from add-ons (with resume), and
  torrent streaming (off by default; turning it on explains that peers see your IP address).
* Phones, tablets and Android TV; dark and light themes; reduced-motion support; a guided first
  run.

## Privacy

Your videos stay where they are, in Telegram or on your device; nothing is uploaded. To find
posters and details, only a title and year parsed from a file name are sent to a movie database,
never message text, chat names or file names. Image proxies and DNS over HTTPS (used only when a
network blocks a host) are disclosed in Settings and can be turned off. Signing out removes the
account's data from the device. No analytics, no ads. Details: [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md).

## Install

Download the APK for your device from the [Releases page](https://github.com/Deepakraj1207/yourstream/releases)
(Android 8.0 or newer). The app offers later updates itself, checks each download against the
release's checksums, and Android only installs it over the previous version when the signature
matches.

## Build

```bash
cp secrets.properties.example secrets.properties   # your Telegram api_id / api_hash (my.telegram.org)
./gradlew test :app:assembleDebug
```

[docs/TELEGRAM_SETUP.md](docs/TELEGRAM_SETUP.md) explains the credentials, sign-in and sign-out.
CI (`.github/workflows/ci.yml`) runs the tests, lint, a debug build and a minified release build
on every push; releases are built and signed by `.github/workflows/release.yml`.

## State of testing

Everything is covered by JVM tests (Robolectric, real Room databases, Media3 on simulated
Telegram files). Parts that depend on a real device, TDLib, the Keystore or Android's file
pickers are listed with step-by-step device checks in
[docs/IMPLEMENTATION_PROGRESS.md](docs/IMPLEMENTATION_PROGRESS.md); those marked "needs
real-device testing" have not been verified on a phone yet.

## Documentation

| Topic | Document |
|---|---|
| Plan, status and device checks per area | [IMPLEMENTATION_PROGRESS.md](docs/IMPLEMENTATION_PROGRESS.md), [PRODUCT_AUDIT.md](docs/PRODUCT_AUDIT.md) |
| Architecture and module interfaces | [ARCHITECTURE.md](docs/ARCHITECTURE.md), [API.md](docs/API.md) |
| Security and privacy | [SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) |
| Telegram sign-in, sign-out, credentials | [TELEGRAM_SETUP.md](docs/TELEGRAM_SETUP.md) |
| Indexing, file-name parser | [INDEXING.md](docs/INDEXING.md), [PARSER.md](docs/PARSER.md) |
| Library, metadata, catalogs | [LIBRARY.md](docs/LIBRARY.md), [METADATA.md](docs/METADATA.md), [CATALOGS.md](docs/CATALOGS.md) |
| Files on the device | [LOCAL_MEDIA.md](docs/LOCAL_MEDIA.md) |
| Playback, streaming, tracks, add-on streams | [PLAYBACK.md](docs/PLAYBACK.md), [ADAPTIVE_STREAMING.md](docs/ADAPTIVE_STREAMING.md), [TRACKS.md](docs/TRACKS.md), [STREAMS.md](docs/STREAMS.md) |
| Search, assistant, watch intelligence | [SEARCH.md](docs/SEARCH.md), [ASSISTANT.md](docs/ASSISTANT.md), [WATCH.md](docs/WATCH.md) |
| UI, UX, accessibility | [UI.md](docs/UI.md), [UX.md](docs/UX.md) |
| Robustness and diagnostics | [ROBUSTNESS.md](docs/ROBUSTNESS.md) |
| Releasing, shrinking releases with R8 | [RELEASE.md](docs/RELEASE.md), [RELEASE_R8.md](docs/RELEASE_R8.md) |

## License

YourStream is free software under the [GNU General Public License v3.0](LICENSE). It bundles
components under their own licenses (listed in the app under Settings → About → Open-source
licenses), among them the FFmpeg audio decoder for Media3 (Jellyfin build, GPL-3.0), which is why
the app as a whole is GPL-3.0.
