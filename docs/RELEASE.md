# Releasing

## Before a release

1. CI is green on the commit (tests, lint, debug build, minified release build).
2. `docs/IMPLEMENTATION_PROGRESS.md` is up to date; `.github/release-notes.md` has a "What's new"
   section for the version.
3. Upgrade path: `MigrationTest.upgradeFromTheV0_10_0ReleaseKeepsTheUsersData` (or a newer
   equivalent from the last released schema) and `SettingsUpgradeTest` pass. Bump them when a
   release changes the schema or the settings format.

## Building and publishing

Actions → **Release APK** → Run workflow:

* **version**: the tag, e.g. `v0.11.0` (the version name is the tag without `v`; the version code
  is the workflow's run number, so it always grows);
* **prerelease**: tick it for a version that still needs device checks. It appears on the
  Releases page but the in-app updater does not offer it (it reads GitHub's "latest release",
  which skips pre-releases);
* **minify**: only after a minified build passed `docs/RELEASE_R8.md` on a device.

The workflow runs the unit tests, builds the universal and per-CPU APKs, fails unless every APK is
signed with the release key (`RELEASE_CERT_SHA256`) and carries the Telegram API credentials,
writes `SHA256SUMS.txt` (the in-app updater checks downloads against it) and publishes.

## After publishing a pre-release

1. Install it over the previous release on a phone (download the APK from the release page):
   Android refuses the install if the signature differs, so a successful install proves the key.
2. Run the device checks in `docs/IMPLEMENTATION_PROGRESS.md` that matter most: sign-in, first
   run, indexing a real channel, playback (background, seeking, tracks), sign-out.
3. Check the old data survived: library, continue watching, favorites, settings (torrents are
   off after updating from 0.10: expected).
4. Promote: on the release page, Edit → untick "Set as a pre-release", tick "Set as the latest
   release" (or `gh release edit v0.11.0 --prerelease=false --latest`). From then on the in-app
   updater offers it.

A problem found before promotion costs nothing: fix, and publish the next version (a release
tag cannot be reused).
