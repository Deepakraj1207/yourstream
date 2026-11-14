# R8 rules for release builds made with -Pyourstream.minify=true.
# Libraries ship their own rules (Room, Hilt, WorkManager, Media3, OkHttp, Coil, Ktor,
# kotlinx.serialization and coroutines); these cover what native code reaches by name.

# TDLib's JNI bridge (also in :telegram:tdlib's consumer rules, repeated so the app owns the list).
-keep class org.drinkless.tdlib.** { *; }
-keep class dev.g000sha256.tdl.** { *; }

# libtorrent4j: SWIG-generated JNI classes, fields and callbacks are looked up from native code.
-keep class org.libtorrent4j.** { *; }

# Jellyfin's FFmpeg decoder for Media3: JNI methods and the decoder classes native code calls back.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Readable stack traces in crash reports (the mapping file is kept with each release build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
