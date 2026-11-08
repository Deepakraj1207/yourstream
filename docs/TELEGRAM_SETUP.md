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
See "Known problems".

Logging out calls TDLib `logOut`. That ends the session on Telegram's servers and deletes the
local database. Uninstalling the app also deletes it.

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

## 5. Manual test script (the Phase 1 acceptance path)

1. Launch "YourStream (debug)". The header shows `TDLib: Running`, the network state, and
   `auth: WaitPhoneNumber`.
2. Enter your number with the country code, e.g. `+91 98765 43210`, and tap **Send code**.
   - Wrong format: you get an error and nothing is sent to Telegram.
3. Enter the code from the Telegram app or SMS. The screen shows the delivery method and
   whether a resend is possible.
   - Wrong code: "The code is wrong." You stay on the code step.
4. If the account has two-step verification, enter the password. The hint is shown.
5. The header shows `auth: Ready` and "Logged in as …". The first 50 chats load.
6. Use **ALL / PRIVATE / GROUPS / CHANNELS** and **Main / Archive** to filter. Tap **Load more**
   until "— end of list —" appears.
7. Tap a chat. You see its id, type, username, member count, description, protected-content
   flag, last message time and lists. Back returns to the list.
8. **Session persistence:** force-stop the app and reopen it. You land on `Ready` directly, with
   no login.
9. **Reconnect:** turn on airplane mode. The network shows `WAITING_FOR_NETWORK`. Turn it off
   and it returns to `READY` within seconds. **Reconnect** forces a re-check.
10. **Restart TDLib:** closes and reopens the TDLib instance. You are still logged in.
11. **Log out:** returns to the phone step, and the chat list is cleared from memory.
12. **QR login:** on the phone step tap **Log in with QR code**, then scan with Telegram on
    another device (Settings › Devices › Link Desktop Device).
13. **Logs** shows the app's log (no phone numbers, codes or passwords).

### Test servers (optional)

Set `telegram.useTestDc=true`. Test accounts are separate from production accounts. Numbers
`99966XYYYY` (X = DC 1–3, YYYY random) need no SMS: the code is `X` repeated 5 times
(e.g. DC 2 → `22222`). Do not use test DC for real data.
