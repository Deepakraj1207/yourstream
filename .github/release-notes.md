## Install

1. Download **YourStream-*.apk** below to your Android phone, tablet or TV (Android 8.0 or newer).
2. Open it and allow "Install unknown apps" for your browser or file manager when asked.
   On a TV: `adb install YourStream-*.apk`, or a file manager app.

## First start: your Telegram API ID and hash

This build has no Telegram API credentials built in. Telegram asks every app for an API ID and
hash; you use your own, once:

1. Open <https://my.telegram.org>, log in, choose **API development tools** and create an app
   (any name, platform Android).
2. In YourStream: **Settings → Telegram API** (or **Enter API ID and hash** on the sign-in
   screen), enter **App api_id** and **App api_hash**, then **Save and restart**.
3. Sign in to Telegram and choose the chats that hold your videos.

They are stored only on your device, the hash encrypted.

## Privacy

No account on any other cloud service is needed. Watch statistics and recommendations are
computed on the device. Syncing progress across devices is optional, off by default, and
end-to-end encrypted in your own Saved Messages.
