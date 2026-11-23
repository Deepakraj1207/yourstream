## What's new in 0.13.2

Windows app:
- **No more freezes** when closing a video or switching episodes, and smoother scrolling in the
  library (posters load in the background).
- **Faster start and seeking:** the player is ready right after sign-in instead of loading on
  every Play, starts after 1.5 s of video instead of 4 s, and a seek no longer competes with the
  old position for the download.
- **Audio tracks named properly**, e.g. "Tamil · DD 5.1 · AC3 5.1". The language you pick is
  remembered and chosen automatically next time; the same for subtitles (off unless you turn them
  on).
- **New player design:** the controls sit over the video and hide while it plays. Big −10 s /
  play / +10 s in the middle, seek bar with remaining time, Audio, Subtitles, Speed,
  Fit/Fill/Stretch, volume with the mouse wheel, "Resumed from … · Start over". Keys: Space,
  ←/→ (Shift for 60 s), ↑/↓, M, F, N, Esc.
- Fixed: a stripe of stray pixels at the bottom of some videos.

Android: no changes in this version.

## What's new in 0.13.1

- **Windows installer half the size:** 144 MB instead of 294 MB. The installer now carries only
  what Windows needs: the Telegram library for Windows alone, just the icons the app uses, and
  VLC without the parts a built-in player never uses. Playback is unchanged.
- Android: no changes in this version.

## What's new in 0.13.0

- **YourStream for Windows (first version).** Download `YourStream-…-windows-x64.msi` below and
  run it (no administrator rights needed; if Windows SmartScreen warns, choose *More info → Run
  anyway*: the installer is not code-signed). Sign in with Telegram (phone number or QR code),
  choose your chats, and play your library on the PC: movies with their versions (4K first),
  series with seasons and episodes, posters, search, resume, next episode, audio and subtitle
  tracks, fullscreen and keyboard controls. Videos stream while downloading, played by VLC, which
  comes inside the installer. Not in the Windows app yet: add-ons and Discover, movies split into
  pieces, files on the PC, downloads and sync with the phone.
- The APK for any device is now named **`…-universal.apk`**, so it is easy to find among the
  others. The app's updater still picks the right one by itself.

## What's new in 0.12.4

- **Much faster indexing of very big channels** (100,000+ files): the history is read in 4 parts
  at once, each resuming exactly where it stopped. Requests slow down by themselves when
  Telegram says "too fast". Titles from the post above are filled in after the scan.
- **Haptic feedback:** a light vibration when you switch tabs, toggle settings, play, seek, swipe
  in the player and more. Turn it off in Settings → Appearance → Accessibility.
- **Title page:** no more flicker while TMDB loads. The poster shows at once, and the backdrop
  fades in over it.
- **Streams page:** the add-on buttons are no longer cut off. Sort & filter and refresh have their
  own row.
- **Player:** no bright strip beside the camera in landscape, and the controls stay centred.
  Buffering shows as a ring around the Play button, not a spinner behind it.

## What's new in 0.12.3

- **The hero carousel no longer reloads** when you come back to Home from another tab, and it
  shows at once when the app starts (the last carousel is kept and refreshed in the background).
  The catalog rows are kept too.
- **Manga style for the hero carousel:** inked comic panels with speed lines, a red FEATURED
  caption, manga lettering, ink boxes for the rating and language, and square dots.
- **Player alignment:** the title, times, timeline and buttons share one edge. Rewind, play and
  forward sit on one line. The times sit over the ends of the timeline. The buttons are centred,
  all the same height, and wrap onto a second line in portrait instead of running off the
  screen. The quality badge matches the icons, and the Audio, Subtitles and Speed menus line up.

## What's new in 0.12.2

- Fixed: the old home showed for a moment at start before the hero carousel replaced it.
- **Carousel like Nuvio's** (with your TMDB key): the title's logo over artwork without the title
  printed on it, instead of the title twice.
- Fixed: title logos and production company logos had a grey box behind them.
- The TMDB score shows out of 10 ("6.7"), like IMDb.

## What's new in 0.12.1

- **Fixed: the app closed while streams loaded** (0.12.0). Streams with and without a file size
  could not be sorted together.
- **Title page like Nuvio's, from TMDB** (with your TMDB key in Settings → TMDB): the title's
  logo over the artwork, genres, **Play**, year · length · age rating, IMDb and TMDB scores,
  director and writer, the overview with Show More, production companies, cast with photos,
  trailers (open in YouTube), movie details, and **More Like This**. Without a TMDB key, your
  add-ons' details fill the same page.
- **Play opens the streams** on their own page, like Nuvio. They start loading when you open the
  title, so they are ready. For series, tap an episode to see its streams.
- **+ in the top bar** adds a title you have on Telegram to My library.

## What's new in 0.12.0

- **One poster per title.** A movie or series you have on Telegram shows once, with a blue
  "Telegram" badge, instead of a second poster. Its page lists **your Telegram files first, as a
  stream source**, above the add-on streams, with quality, size and audio languages. Tapping one
  plays it in your player (resume, tracks and split files work as before).
- **Sort & filter streams:** sort by best quality, largest, smallest or source order. Filter by
  quality (4K / 1080p / 720p / SD), source (Telegram / add-ons), direct or torrent, and audio
  language. Your choice is remembered for every title.
- **Search like Nuvio:** recent searches, and **Discover** (type, catalog and genre over a poster
  grid) before you type. While typing, your Telegram titles come first, then one row per add-on.
- **Smoother navigation bar:** in Adaptive mode the labels fade and fold away together as the bar
  shrinks, and the highlight slides between tabs. It reacts only after a real scroll.
- Home: "Recently added" is tagged **From Telegram**, and the separate Series row is gone.
- Fixed: the hero carousel stopped halfway between two slides.

## What's new in 0.11.5

- **Navigation like Nuvio's** in the classic theme: a floating bar over the content, the current
  tab in a lighter capsule. **Adaptive** (default) shrinks it to icons while you scroll down and
  brings it back when you scroll up; also Always expanded, Always compact, Classic, and Panel Grid
  for the manga look (Settings → Layout → Navigation bar, with a preview and Glass glow).
- **Hero carousel**: pick up to 3 catalogs in Settings → Layout → Home layout → Hero catalogs, and
  Home opens on a full-width carousel of their titles (title, genre, length, year, rating,
  View Details). Without hero catalogs, the hero shows what to continue or your newest title.
- **Posters are matched automatically.** "Check matches" is gone: the best match is applied when it
  is clearly right, otherwise the title keeps its file name. Titles that were waiting are matched
  now. "Fix match" on a title still corrects a wrong one.
- Fixed: rows below jumped up and down while you scrolled posters sideways.

## What's new in 0.11.4

- **Player like Nuvio's:** lock button (hides the controls and ignores touches until you unlock),
  labelled buttons (Fit, speed, Subs, Audio, Episodes), time pills, **Sources** to switch to
  another file of the same title without losing your place, **swipe sideways to seek**, and
  **Subtitles → Style** (size, colour, position, bold, background).
- **Settings → Layout like Nuvio's:** Home layout (hero, "Popular · Movies" labels, hide
  unreleased titles, and every add-on catalog: hide, reorder, rename), Continue Watching (wide or
  poster cards, resume or open details), Poster card style (width, corners, labels, live preview),
  Streams (size badges, add-on logos), Detail page (Cinematic background, episodes as a row,
  overview and cast), Navigation bar (Adaptive, Panel Grid, Floating with glow, Classic).
- **Add-ons page like Nuvio's:** overview, logo and version on each card, move up / down (also
  orders Home's rows and the stream groups), refresh, settings page for configurable add-ons,
  delete with a confirmation.
- **Only Cinemeta is built in.** Every other add-on is added by you (the built-in Indian catalogs
  are gone after this update; add them again with their links if you used them).
- **Opening logo** when the app starts (a tap skips it).
- Fixed: a full-screen "Connect Telegram" flashed at launch before switching to the normal Home.

## What's new in 0.11.3

- **4K movies split into pieces play as one movie.** Files like "Movie.2160p.mkv.001", ".002",
  ".003" (cut to fit Telegram's upload limit) now show as one version ("9 pieces") and stream as
  one file: full length, seeking anywhere, audio and subtitle tracks, resume. The next piece
  starts downloading before the current one ends. A missing piece is named instead of failing
  midway. "Download" saves every piece. Split archives (.7z.001, .part1.rar) are not supported.
- **Add-ons: refresh button** (or pull down): asks every add-on again, picks up new names and
  catalogs, and says how many answered.
- **Streams grouped by add-on:** each add-on's streams appear as soon as it answers, under its
  name, with chips to show all or one add-on and a refresh button. Add-ons that found nothing or
  did not answer are named.
- **New manga navigation bar** ("Panel Grid"): slanted comic panels, the current one inked.
- Fixed: switching episodes while a slow add-on answered could show the previous episode's
  streams.

## What's new in 0.11.2

- **Fixed: Telegram sign-in could keep loading forever.** Closing the app quickly a few times
  (or installing an update) could start it in a safe mode where Telegram never started. Now
  only real crashes count, and the sign-in screen always explains a slow or stopped start, with
  a button to start Telegram.
- Library status: buttons no longer squeeze the text on phones.
- Add-ons: the Nuvio plugins tab is gone (plugin links get a short note).

## What's new in 0.11.1

- **Titles fixed for names joined with underscores** ("@Channel_LinkZ_Movie_2023_…"): such files
  were grouped under one wrong title ("Tel") or named after the post above them ("Link to this
  message"). Site brands like "Toonworld4all" are no longer part of titles, and "S02_EP_01_05"
  is read as episodes 1–5. Your library is re-read and regrouped once after the update.
- **Navigation:** Home, Search, Library, Settings (Movies and Series open from Home and the
  Library), and a new manga-style navigation bar.

## What's new in 0.11

- **Guided first run** and a simpler navigation.
- **Files on this device**: add folders (no permission needed) or all videos; they join the library.
- **Offline downloads** of Telegram videos, and **resume for add-on streams**.
- **Background playback** with lock-screen and headset controls.
- **Indexing controls**: pause, resume, stop; Wi-Fi only; waits on a low battery.
- **Storage** page: see and clear each cache.
- **Signing out removes the account's data** from the device.
- Privacy: image proxies and DNS over HTTPS are explained and can be turned off; your TMDB key is
  stored encrypted; updates are checked against the release's checksums.
- **Torrents are off** after this update, even if they were on. Turn them on again in
  Settings → Playback if you use them (peers see your IP address).
- Reduce motion option; many fixes.

## Install

1. Download the APK for your device below (Android 8.0 or newer):
   - **YourStream-…-arm64-v8a.apk**: almost every phone and tablet from the last years.
   - **YourStream-…-armeabi-v7a.apk**: older phones and many TV sticks and boxes (32-bit).
   - **YourStream-…-universal.apk**: works on any device, larger download. Not sure? Take this one.
2. Open it and allow "Install unknown apps" for your browser or file manager when asked.
   On a TV: `adb install YourStream-*.apk`, or a file manager app.

Already installed? The app offers updates itself and downloads only the APK for your device.

## First start

Sign in to Telegram with your phone number (or scan a QR code from Telegram on another device),
then choose the chats that hold your videos.

## Privacy

No account on any other cloud service is needed. Watch statistics and recommendations are
computed on the device. Syncing progress across devices is optional, off by default, and
end-to-end encrypted in your own Saved Messages.
