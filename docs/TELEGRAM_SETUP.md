# Telegram setup: credentials, configuration and testing

## 1. Get API credentials

Every Telegram client needs its own **api_id** and **api_hash**.

1. Open <https://my.telegram.org> and log in with your phone number.
2. Go to **API development tools** and create an application. Any title and short name work;
   platform "Android".
3. Note **App api_id** (an integer) and **App api_hash** (32 hex characters).

These identify *the app*, not your account, but treat them as secrets: a leaked, abused id
gets blocked by Telegram (`API_ID_PUBLISHED_FLOOD`) for everyone using it. Each fork or
developer should use their own.

## 2. Supply them to the build (never to git)

`app/build.gradle.kts` reads two keys, `telegram.apiId` and `telegram.apiHash`, and uses the
first source that has them:

| Priority | Source | Typical use |
|---|---|---|
| 1 | Gradle property: `-Ptelegram.apiId=…` or `~/.gradle/gradle.properties` | per-machine, outside the repo |
| 2 | Environment variables `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` | CI (store as encrypted secrets) |
| 3 | `secrets.properties` in the project root | local development |
| 4 | `local.properties` in the project root | local development |

`secrets.properties` and `local.properties` are in `.gitignore`. Start from the template:

```bash
cp secrets.properties.example secrets.properties
```

Then edit the two values. Optional key: `telegram.useTestDc=true` (or `TELEGRAM_USE_TEST_DC`)
switches to Telegram's test servers.

What happens with the values:
- They become `BuildConfig.TELEGRAM_API_ID` / `TELEGRAM_API_HASH` in the `:app` module only.
- `dev.yourstream.di.AppModule` puts them in `TelegramConfig`, which is handed to TDLib.
- **Missing values do not break the build.** The app starts and the debug screen says
  "Telegram is not configured", and TDLib is never started. Malformed values (a non-integer id,
  a non-hex hash) fail the build with a clear message.
- They are never logged. `TelegramConfig.toString()` prints only "set" or "missing".

> Note: anything compiled into an APK can be extracted from it. That is normal and accepted
> for Telegram clients. "Never in git" protects the values from being published and reused;
> it cannot hide them from someone who unpacks your APK.

## 3. Where the session is stored

| What | Where | Why |
|---|---|---|
| TDLib database (auth keys, session, chat cache) | `noBackupFilesDir/tdlib/db` | Persists across restarts, so you stay logged in. Excluded from all backups (`allowBackup=false` plus `data_extraction_rules.xml`) |
| Downloaded files | `cacheDir/tdlib/files` | Android may clear it |
| TDLib internal log | `cacheDir/tdlib/tdlib.log` (10 MB cap; verbosity 2 in debug builds, 1 in release) | Diagnostics |

The database is not encrypted beyond Android's app sandbox (`databaseEncryptionKey` is empty).
This is deliberate for now: Android's file-based encryption already encrypts app data at rest,
backups are disabled, and a key kept in the Android Keystore protects little against an attacker
who can read the sandbox (root) while adding a way to lose the session (Keystore failures on some
devices make TDLib refuse to open its database). Revisit with real-device data.

### Signing out (since 0.11)

One rule: **while Telegram is signed out, no account data stays on the device**
(`AccountSession`, `DeviceAccountData`, `AccountData`).

* **Sign out** (Settings → Account) asks first and says what is removed. It calls TDLib `logOut`,
  which ends the session on Telegram's servers and deletes TDLib's database and files.
* The app then removes everything else of the account: indexed files, library, metadata
  matches, watch history and progress, track choices, the search index, indexing work, posters
  and thumbnails, cached web and torrent streams, the progress-sync key, logs, events and crash
  reports. **Kept** (device settings): app settings, language preferences, add-ons, the TMDB key.
* A session ended elsewhere (terminated from another device, expired, account deleted) is
  handled the same way; the sign-in screen then says "You were signed out" until the next sign-in.
* Whenever the app is signed out and finds account data (left by a version before 0.11, or a
  cleanup cut short), it removes it. Every sign-in passes through the signed-out state, so a
  second account never sees the first one's library: switching accounts is sign out, then sign in.
* Uninstalling the app deletes everything.

## 4. Build and run

You need Android SDK platform 37 and JDK 21.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests. They need no device and no credentials:

```bash
./gradlew test
```

## 5. Manual test script (real device, real account)

Needs a build with the API credentials (section 2) and a phone with Telegram on another device.

1. Fresh install, open **Settings → Telegram account**. Enter your number (country picker + number)
   and tap **Send code**.
   - Too short a number: the button stays disabled. Wrong number: "The phone number is invalid."
2. Enter the code from Telegram (or SMS). A wrong code says "The code is wrong." and keeps you on
   the code step. **Resend** works when Telegram offers another way.
3. With two-step verification: the password step shows your hint; a wrong password says
   "Wrong password."
4. Signed in: the account screen shows your name. Choose chats in **Telegram sources**; indexing
   starts.
5. **Session persistence:** force-stop the app and reopen: still signed in, no code.
6. **Offline:** airplane mode, then try to sign in on a second device's fresh install: "Can't
   reach Telegram. Check your internet connection and try again."
7. **QR login:** on the phone step tap **Use a QR code**, scan it in Telegram on another device
   (Settings → Devices → Link Desktop Device).
8. **Sign out:** Settings → Telegram account → **Sign out** asks first. Confirm: "Signing out and
   removing your library…", then the phone step. Home and Library are empty; Settings (theme,
   languages, add-ons, TMDB key) are unchanged.
9. **Session ended elsewhere:** sign in again, then on another device end the session
   (Telegram → Settings → Devices → terminate "YourStream"). The app returns to the phone step
   with "You were signed out", and the library is gone.
10. **Account switch:** sign in with a second account: none of the first account's titles,
    progress or posters appear.
11. **Flood limit:** many wrong codes in a row: "Too many attempts. Telegram asks you to wait …".
12. Logs (adb logcat, tag prefix `YS/`) contain no phone numbers, codes or passwords.

* **Files on this device** (Phase 8) are part of the account's library: their index rows go with
  everything else. The choice of folders is a device setting and stays; they are scanned again
  after the next sign-in (`docs/LOCAL_MEDIA.md`).

### Test servers (optional)

Set `telegram.useTestDc=true`. Test accounts are separate from production accounts. Numbers
`99966XYYYY` (X = DC 1–3, YYYY random) need no SMS: the code is `X` repeated 5 times
(e.g. DC 2 → `22222`). Do not use test DC for real data.
