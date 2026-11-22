#!/usr/bin/env bash
# Puts VLC's player libraries (libvlc, libvlccore and the plugins: VLC 3, 64-bit, GPL/LGPL) into
# desktop/appResources/windows/vlc, from where the Windows installer ships them and the app plays
# videos without a separate VLC install. Run on the Windows build machine (CI); the folder is not
# committed.
#
# The zip comes from VideoLAN's own server and is checked against the SHA-256 VideoLAN publishes
# next to it. Any failure stops the script with exit code 1; the caller decides whether to build
# without VLC (the app then asks the user to install VLC).
set -euo pipefail

BASE="https://download.videolan.org/pub/videolan/vlc/last/win64/"
OUT="desktop/appResources/windows/vlc"
WORK="${RUNNER_TEMP:-/tmp}/vlc-download"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"

ZIP=$(curl -fsSL "$BASE" | grep -oE 'vlc-[0-9][0-9.]*-win64\.zip' | sort -uV | tail -1)
if [ -z "$ZIP" ]; then echo "No VLC win64 zip listed at $BASE"; exit 1; fi
echo "VLC: $ZIP"
curl -fsSL -o "$WORK/$ZIP" "$BASE$ZIP"
curl -fsSL -o "$WORK/$ZIP.sha256" "$BASE$ZIP.sha256"
EXPECTED=$(grep -oE '[0-9a-f]{64}' "$WORK/$ZIP.sha256" | head -1)
ACTUAL=$(sha256sum "$WORK/$ZIP" | cut -d' ' -f1)
if [ -z "$EXPECTED" ] || [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Checksum mismatch for $ZIP: expected '$EXPECTED', got '$ACTUAL'"; exit 1
fi
echo "Checksum OK: $ACTUAL"

unzip -q "$WORK/$ZIP" -d "$WORK/x"
SRC=$(ls -d "$WORK"/x/vlc-*/ | head -1)
cp "$SRC/libvlc.dll" "$SRC/libvlccore.dll" "$OUT/"
cp -r "$SRC/plugins" "$OUT/"
# VLC's licence and authors travel with it.
for f in COPYING.txt AUTHORS.txt README.txt; do [ -f "$SRC/$f" ] && cp "$SRC/$f" "$OUT/VLC-$f"; done
echo "${ZIP%-win64.zip}" > "$OUT/VERSION.txt"
du -sh "$OUT"
