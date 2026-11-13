# Files on this device

Since Phase 8 the library can include videos stored on the device next to Telegram videos.
Settings → Content → **Files on this device**.

## Sources

| Kind | How it is added | Permission | What becomes readable |
|---|---|---|---|
| Folder | System folder picker (Storage Access Framework, `OpenDocumentTree`) | None; the picker grants read access to that folder, kept with `takePersistableUriPermission` | That folder and its sub-folders only (hidden `.folders` skipped) |
| All videos on this device | "Add all videos" | `READ_MEDIA_VIDEO` (Android 13+; on 14+ the user may allow only selected videos, `READ_MEDIA_VISUAL_USER_SELECTED`), `READ_EXTERNAL_STORAGE` (12 and older), asked at that moment | The videos in the system's MediaStore that were allowed (all, camera clips included, or the selected ones) |

Choosing folders is the recommended way: no permission, and only what the user picks.
Removing a source stops scanning it, removes its videos from the library (not from the
device) and gives the folder access back.

## Design: a source in the existing index

The index was built for Telegram (`indexed_chat`, `media` keyed by chat and message). Rather than
rebuild it, local sources are stored the same way (schema v14, AutoMigration 13→14):

* `indexed_chat.source` = `TELEGRAM` (default for every existing row) or `LOCAL`;
* local sources take ids from a reserved range (`LocalSourceIds`, from −4·10¹⁸ down), far
  from any Telegram chat id;
* `media.locator` = the file's `content://` address (null for Telegram files);
* a local file's "message id" is a hash of its stable key (document id or MediaStore id).

So the parser, duplicate detection, the library, metadata matching, search, watch history and
Library status treat local files exactly like Telegram ones. The same film on Telegram and on
the device becomes one title with two versions (tested).

## Scanning (`media/indexer/.../local/`)

* `LocalFileLister` lists video files from the system's own index (document provider or
  MediaStore). No file is opened. Video = `video/*` MIME type or a known extension.
* `LocalScanner` turns each file into the parser's input, writes it with `savePage`, and marks
  files that are no longer listed as deleted. Re-running it is always safe (it lists the whole
  source each time; changed files are updated in place).
* `LocalScanWorker` runs a scan through the same WorkManager queue as a chat
  (`index-chat-<id>`), so Pause / Resume / Stop in Library status work the same. It needs no
  network; the battery rule from Settings → Content applies.
* Scans run when a source is added, when Telegram becomes ready (each app start and after a
  sign-in), on the periodic sync, and on "Scan again".
* Lost access (a folder deleted, the permission revoked) leaves the files in place and shows
  a plain message on the source.

## Playback

`LibraryMediaSourceResolver` sees the version's `locator`, checks the file can still be opened
(`LocalFileCheck`) and returns a `PlayableMedia` with `localUri`. Media3 reads it through its
default factory; the Telegram streaming steps (download window, prefetch, file release) are
skipped. A local file deleted since the last scan falls through to the next version (e.g. the
Telegram copy), or says "no longer on this device".

## Signing out

The library belongs to the signed-in account, so it follows the rule in
`docs/TELEGRAM_SETUP.md`: signing out removes the local files from the index too, and scans run
only while signed in (otherwise every start would wipe and rebuild them). The **choice** of
sources is a device setting (`local_sources` preferences, with the folder access) and stays; the
sources are scanned again after the next sign-in.

## Privacy

Files are read on the device and never uploaded. As for Telegram files, only the title and year
parsed from a file name are sent for posters and details (`docs/SECURITY_REVIEW.md`).

## Tests

`LocalScannerTest` (real Room and parser; the listing is a stand-in), `LocalMediaLibraryTest`
(real scanner + library sync: titles, episodes, one title for a Telegram and a local copy,
removal), `LibraryMediaSourceResolverTest` (local playback address, a gone file falls through),
`LocalFilesTest` (screen), `MigrationTest.v13ToV14…`.

## Needs a device

The Storage Access Framework and MediaStore listing (`AndroidLocalFileLister`), the persisted
folder access across restarts, the permission prompt, and playback of `content://` files are
not exercised by the JVM tests. Check on a device:

1. Add a folder with a few films and an episode; the library fills; posters appear.
2. Play one; seek; resume later; background playback.
3. Restart the app; the folder is still listed and scans again.
4. Delete a file in a file manager; "Scan again"; it is gone (or another version plays).
5. Add all videos; allow the permission; camera clips land in "Files without a title".
6. Revoke the permission in Android settings; the source shows the plain message.
7. Sign out; sign in; the folder is scanned again without picking it.
8. An SD card folder and a USB-OTG folder, if available.
