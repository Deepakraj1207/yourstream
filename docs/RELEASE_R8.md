# Shrinking releases with R8

Release builds can be shrunk, optimised and obfuscated with R8:

```bash
./gradlew :app:assembleRelease -Pyourstream.minify=true
```

It is **off by default** (`app/build.gradle.kts`, property `yourstream.minify`). The release
workflow has a `minify` checkbox under Actions → Release APK → Run workflow; tag pushes build
without R8. CI builds a minified release on every push, so broken keep rules or missing classes
fail the build. Whether the shrunk app *runs* can only be checked on a device, because TDLib,
libtorrent and the FFmpeg decoder call Java code from native code, which no JVM test exercises.

## What the rules keep (`app/proguard-rules.pro`)

| Native library | Java classes it binds to (read from the `.so` files) | Rule |
|---|---|---|
| `libtdjsonjava.so` (TDLib) | `org.drinkless.tdlib.JsonClient` (`createClientId`, `send`, `receive`, `execute`) | `org.drinkless.tdlib.**`, `dev.g000sha256.tdl.**` |
| `libtorrent4j.so` | `org.libtorrent4j.swig.libtorrent_jni` and its callbacks (`alert_notify_callback`, …) | `org.libtorrent4j.**` |
| `libffmpegJNI.so` | `androidx.media3.decoder.ffmpeg.FfmpegLibrary`, `FfmpegAudioDecoder` | `androidx.media3.decoder.ffmpeg.**` |

Room, Hilt, WorkManager, Media3, OkHttp, Coil, Ktor, kotlinx.serialization and coroutines ship
their own rules. The app's own code uses no reflection. Stack traces keep line numbers; the
mapping file is at `app/build/outputs/mapping/release/mapping.txt` for each build.

Checked statically on 12 Nov 2026 (`dexdump` of the minified APK): every class above is present
with its native methods, as are `YourStreamApp`, `MainActivity` and `PlaybackService`.

## Device check before turning it on for releases

Install a minified build **on a test device or a second profile** (it is signed like a normal
release; installing it over a non-minified build of the same key keeps your data). Then:

1. Launch: the first run opens; no crash on start.
2. Sign in with a phone number and code, and with a QR code (TDLib JSON bridge).
3. Choose a chat; indexing finds files; the library fills; posters load (Room, WorkManager,
   Hilt workers, Coil, TMDB over Ktor).
4. Play a Telegram video; seek; change audio and subtitle tracks; background playback with the
   screen off; lock-screen controls (Media3, media session).
5. Play a file with an audio format the device lacks (e.g. DTS/TrueHD): sound comes through the
   FFmpeg decoder (Settings → Playback → app audio decoders first).
6. Add-ons: open a catalog title and play a web stream.
7. Torrents (if used): turn them on, play a torrent stream (libtorrent JNI and callbacks).
8. Progress sync on, sync once (kotlinx.serialization of the shared file).
9. Settings → Storage: sizes shown; clear each cache.
10. Sign out: the account data is removed; sign in again.
11. Check for updates (GitHub JSON parsing; the download is checked against `SHA256SUMS.txt`).
12. Leave it running in the background for a while, come back: no crash.

If all pass, record the result in `docs/IMPLEMENTATION_PROGRESS.md` (Phase 7) and tick
`minify` when running the release workflow (or change the default in `app/build.gradle.kts`).
A crash: run `retrace` with the build's `mapping.txt` on the stack trace and add the missing
`-keep` rule.
