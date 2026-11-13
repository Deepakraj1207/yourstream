# Security and privacy review

State of the app as of Phase 7 (12 Nov 2026). Findings come from `docs/PRODUCT_AUDIT.md` §5 and
this phase's code review; each shows what was done and what is left. Where something is
verified only on the JVM or only statically, it says so.

## 1. What the app is and what it protects

YourStream signs in to the user's own Telegram account (TDLib, the official client library),
reads the chats the user chooses for video files, and plays them. Optional extras: catalog
add-ons (Stremio-style HTTP APIs), web and torrent streams from add-ons, progress sync through
the user's Saved Messages.

Assets, most sensitive first:

1. The Telegram session (TDLib database: auth keys). Whoever holds it can read the account.
2. The login itself (phone number, code, 2FA password) while signing in.
3. What the user has and watches: chat names, file names, library, watch history.
4. API keys: the app's Telegram API id/hash (public by nature in any Telegram client), the
   user's TMDB key, the progress-sync key.

## 2. Data that leaves the device

| Destination | When | What is sent | Who sees it |
|---|---|---|---|
| Telegram (TDLib, MTProto) | Signed in | Everything a Telegram client sends: sign-in, chat list, message search in chosen chats, file downloads | Telegram |
| TMDB (`api.themoviedb.org`, `api.tmdb.org`) | Matching titles | Title, year, type (movie/series) and language parsed from a file name (`MetadataQuery`). **Never** message text, chat names, file names or user ids | TMDB, with the device's IP |
| Cinemeta (`v3-cinemeta.strem.io`) | Matching without a TMDB key; catalogs | Same title/year query; IMDb ids of titles opened | Cinemeta |
| TMDB images (`image.tmdb.org`) | Posters | Image paths (which title's poster) | TMDB |
| Image proxies (`wsrv.nl`, `i0.wp.com`) | **Only** when the network blocks a poster host, and only while Settings → Integrations → Image proxies is on (default on) | Poster image addresses | The proxy, with the device's IP |
| DNS over HTTPS (`cloudflare-dns.com`, `dns.google`) | When the system DNS blocks a host (Settings → Secure DNS for blocked sites, default on), or for every host (Secure DNS, default off) | Host names looked up | Cloudflare / Google |
| Catalog add-ons (built-in Indian catalogs, user-added) | Browsing catalogs, opening a catalog title | Catalog requests; the IMDb id of the title opened | The add-on's server |
| Web streams | Playing an add-on stream | The stream request with the add-on's headers | The stream host |
| Torrent peers and trackers | Only after the user turns torrents on and accepts the warning (off by default) | Torrent protocol traffic | Every peer and tracker, which see the device's IP |
| GitHub (`api.github.com`, release assets) | Update check | `User-Agent: YourStream/<version> (Android)` | GitHub |

Files on this device (Phase 8) are read locally and never uploaded; their parsed title and year
go to the metadata providers above exactly like a Telegram file's.

Nothing else: no analytics, no crash upload, no ads. Diagnostics stay on the device unless the
user exports them.

## 3. Data stored on the device

| Data | Where | Protection |
|---|---|---|
| Telegram session | `noBackupFilesDir/tdlib/db` | App sandbox + Android file-based encryption; backups disabled (`allowBackup=false`, data-extraction rules exclude everything). Not TDLib-encrypted: see §5, accepted |
| Downloaded/streamed Telegram files | `cacheDir/tdlib/files` | Sandbox; trimmed above 2 GB of streamed copies; Settings → Storage clears |
| Library, history, search index | Room database | Sandbox; wiped on sign-out (`AccountData`) |
| TMDB key (user's) | SharedPreferences `tmdb` | **AES-256-GCM with an Android Keystore key** (since Phase 7); older plain keys are encrypted on first read and the plain copy removed |
| Progress-sync key | SharedPreferences | AES-256-GCM, Android Keystore |
| Kept downloads list, add-on stream positions | SharedPreferences `downloads`; Room `stream_progress` | Sandbox; both wiped on sign-out |
| Local sources (folders, "all videos") | SharedPreferences `local_sources` + persisted folder access | Sandbox; a device setting (kept at sign-out); the indexed files follow the account (wiped at sign-out) |
| Web stream / torrent / image caches | `cacheDir/stream-cache`, `torrents`, `posters` | Bounded (512 MB / per stream / 300 MB); Settings → Storage shows and clears; wiped on sign-out |
| Logs, events, crash reports | app files | Redacted on write (`Redactor`); wiped on sign-out |

## 4. Platform surface

* **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` (in-app
  updater; blocks a Play Store listing as is), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (background playback), `READ_MEDIA_VIDEO` (with
  `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+ for "selected videos only") /
  `READ_EXTERNAL_STORAGE` (≤ Android 12) asked only when the user chooses "Add all videos on this
  device" (Phase 8). Folders chosen with the system picker need no permission and only that
  folder becomes readable.
* **Exported components:** only the launcher activity. `PlaybackService` is not exported and its
  media session accepts only the system's media controllers, trusted controllers and the app
  itself. The update receiver and the file providers are not exported. No deep links, no WebView.
* **Network security config:** system CAs only (no user CAs). Cleartext HTTP is allowed app-wide
  because add-on streams are sometimes `http://` on a home network; Android cannot allow it per
  host chosen at run time. Everything the app itself calls is HTTPS (table above). Accepted, see §5.
* **Logging:** logcat output is redacted (`AppLogger.forLogcat`); release builds log no stack
  traces. Never logged: API credentials, login codes, session strings (Phase 1, tested).
* **Updates:** the downloaded APK must match the release's `SHA256SUMS.txt` before it is handed
  to the installer (Phase 7, tested on the JVM); Android then checks the signature against the
  installed app. Releases must be signed with the release key; CI checks the certificate.

## 5. Findings

| Severity | Finding | Status |
|---|---|---|
| High | Sign-out left account data behind | **Fixed** (Phase 2): wipe on every sign-out, also when the session ends elsewhere |
| High | Torrent streaming on by default | **Fixed** (Phase 1): off by default, consent dialog states the IP exposure |
| Medium | Release logcat not redacted | **Fixed** (Phase 1) |
| Medium | Releases could be signed with a throwaway key | **Fixed** (Phase 1): release key required, certificate pinned in the workflow |
| Medium | Poster URLs sent to third-party proxies without disclosure | **Fixed** (Phase 7): disclosed in Settings → Integrations → Image proxies, with an off switch (tested: none reach a proxy when off) |
| Medium | DNS over HTTPS used automatically without disclosure | **Fixed** (Phase 7): disclosed, with an off switch for the automatic use (tested: no DoH lookup when off) |
| Medium | TDLib database not encrypted | **Accepted** for now: file-based encryption, no backups; a Keystore-held key adds little against root and can lose the session on devices with Keystore faults. Revisit with device data |
| Medium | Cleartext allowed app-wide | **Accepted**: needed for user-chosen `http://` streams; app endpoints are HTTPS |
| Low | TMDB key in plain SharedPreferences | **Fixed** (Phase 7): Keystore AES-GCM; JVM tests use a stand-in cipher (Robolectric has no Keystore): **needs a device check** |
| Low | Updater did not verify `SHA256SUMS.txt` | **Fixed** (Phase 7) |
| Low | `REQUEST_INSTALL_PACKAGES` blocks Play listing | Open: decide in Phase 10 (a Play flavor without the updater) |
| Low | Background work without an exception handler could crash the app (progress save, sync, torrent engine) | **Fixed** (Phase 7): the shared, logging scopes are used |
| Info | No R8: larger app, readable bytecode | **Prepared** (Phase 7): R8 with keep rules builds in CI; off for releases until the device check in `docs/RELEASE_R8.md` passes |
| Info | No dependency vulnerability scanning | Open: no scanner configured. Suggested: enable GitHub Dependabot alerts in the repository settings (no code change) |

## 6. Not verified

* Everything involving TDLib, the Keystore, Media3 sessions and native code is checked on the JVM
  with fakes or statically; real-device checks are listed in `docs/TELEGRAM_SETUP.md`,
  `docs/PLAYBACK.md` §9b, `docs/RELEASE_R8.md` and `docs/IMPLEMENTATION_PROGRESS.md`.
* No penetration test, no dynamic analysis of the APK, no review of TDLib, libtorrent or FFmpeg
  themselves (third-party native code, kept up to date through their releases).
