package dev.yourstream.media.filename.internal

import java.time.Year

/**
 * The detectors, run in a fixed order: most specific patterns first so they claim their
 * characters before broader patterns could misread them. Each is independent and wrapped by
 * the parser so one failing rule cannot break the others.
 */
internal object Detectors {

    private val I = RegexOption.IGNORE_CASE
    private fun rx(pattern: String) = Regex(pattern, I)

    /** Letters-aware word boundaries (\b is ASCII-only in Java and misses Tamil, Devanagari…). */
    private const val B = "(?<![\\p{L}\\p{N}\\p{M}])"
    private const val E = "(?![\\p{L}\\p{N}\\p{M}])"

    val ALL: List<Pair<String, (Scan, Prepared) -> Unit>> = listOf(
        "resolutionWxH" to ::resolutionWxH,
        "airDate" to ::airDate,
        "seasonEpisode" to ::seasonEpisode,
        "resolution" to ::resolution,
        "subtitles" to ::subtitles,
        "audio" to ::audio,
        "service" to ::service,
        "quality" to ::quality,
        "videoCodec" to ::videoCodec,
        "bitDepth" to ::bitDepth,
        "hdr" to ::hdr,
        "hardsubCode" to ::hardsubCode,
        "dubbed" to ::dubbed,
        "languages" to ::languages,
        "edition" to ::edition,
        "flags" to ::flags,
        "part" to ::part,
        "years" to ::years,
        "lateResolution" to ::lateResolution,
        "releaseGroup" to ::releaseGroup,
    )

    // ------------------------------------------------------------------ episodes

    private val SXXEYY = rx("${B}S(\\d{1,2})\\s?[-]?\\s?EP?\\s?(\\d{1,4})((?:\\s?-?\\s?EP?\\s?\\d{1,4})*)(?:\\s?-\\s?(\\d{1,4})(?![\\dpi]))?$E")
    private val NXNN = rx("${B}(\\d{1,2})x(\\d{2,3})(?:\\s?-\\s?(?:\\d{1,2}x)?(\\d{2,3}))?$E")
    private val SEASON_EPISODE_WORDS = rx("${B}Season\\s?(\\d{1,2})\\s?-?\\s?(?:Episode|Epi|Ep|E)\\s?(\\d{1,4})(?:\\s?(?:-|to|&)\\s?(\\d{1,4}))?$E")
    private val SEASON_RANGE = rx("${B}S(\\d{1,2})\\s?-\\s?S(\\d{1,2})$E")
    private val SEASON_WORDS = rx("${B}Seasons?\\s?(\\d{1,2})(?:\\s?(?:-|to|&)\\s?(\\d{1,2}))?$E")
    private val SEASON_ONLY = rx("${B}S(\\d{1,2})$E")
    private val EPISODE_WORDS = rx("${B}(?:Episodes?|Epi|Ep|E)\\s?(\\d{1,4})(?:\\s?(?:-|to|&)\\s?(?:Episode|Ep|E)?\\s?(\\d{1,4}))?$E")
    private val TRAILING_EPISODES = rx("EP?\\s?(\\d{1,4})")

    fun seasonEpisode(scan: Scan, p: Prepared) {
        scan.firstFree(SXXEYY)?.let { m ->
            val episode = m.groupValues[2].toInt()
            val chained = TRAILING_EPISODES.findAll(m.groupValues[3]).map { it.groupValues[1].toInt() }.lastOrNull()
            val end = (chained ?: m.groupValues[4].toIntOrNull())?.takeIf { it > episode }
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.98f)
            scan.add(Kind.EPISODE, episode, m.range, 0.98f, m.value, end?.let { mapOf("end" to it) } ?: emptyMap(), claim = false)
            return
        }
        scan.firstFree(SEASON_EPISODE_WORDS)?.let { m ->
            val episode = m.groupValues[2].toInt()
            val end = m.groupValues[3].toIntOrNull()?.takeIf { it > episode }
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.95f)
            scan.add(Kind.EPISODE, episode, m.range, 0.95f, m.value, end?.let { mapOf("end" to it) } ?: emptyMap(), claim = false)
            return
        }
        scan.firstFree(NXNN)?.let { m ->
            val episode = m.groupValues[2].toInt()
            val end = m.groupValues[3].toIntOrNull()?.takeIf { it > episode }
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.88f)
            scan.add(Kind.EPISODE, episode, m.range, 0.88f, m.value, end?.let { mapOf("end" to it) } ?: emptyMap(), claim = false)
            return
        }
        scan.firstFree(SEASON_RANGE)?.let { m ->
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.9f, mapOf("end" to m.groupValues[2].toInt()))
        } ?: scan.firstFree(SEASON_WORDS)?.let { m ->
            val end = m.groupValues[2].toIntOrNull()
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.9f, end?.let { mapOf("end" to it) } ?: emptyMap())
        } ?: scan.firstFree(SEASON_ONLY)?.let { m ->
            scan.add(Kind.SEASON, m.groupValues[1].toInt(), m, 0.8f)
        }
        scan.firstFree(EPISODE_WORDS)?.let { m ->
            val episode = m.groupValues[1].toInt()
            val end = m.groupValues[2].toIntOrNull()?.takeIf { it > episode }
            val confidence = if (m.value.startsWith("E", ignoreCase = true) && m.value.length <= 5) 0.75f else 0.85f
            scan.add(Kind.EPISODE, episode, m, confidence, end?.let { mapOf("end" to it) } ?: emptyMap())
        }
    }

    private val DATE = rx("${B}((?:19|20)\\d{2})\\s(0[1-9]|1[0-2])\\s(0[1-9]|[12]\\d|3[01])$E")

    fun airDate(scan: Scan, p: Prepared) {
        scan.firstFree(DATE)?.let { m ->
            scan.add(Kind.AIR_DATE, "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}", m, 0.9f)
        }
    }

    // ------------------------------------------------------------------ picture

    private val WXH = rx("${B}(\\d{3,4})\\s?x\\s?(\\d{3,4})$E")

    fun resolutionWxH(scan: Scan, p: Prepared) {
        scan.firstFree(WXH)?.let { m ->
            val w = m.groupValues[1].toInt()
            val h = m.groupValues[2].toInt()
            if (w in 320..8000 && h in 200..4400 && w > h) scan.add(Kind.RESOLUTION, standardHeight(h), m, 0.95f)
        }
    }

    private val RES_P = rx("${B}(4320|2160|1440|1080|720|576|540|480|360|240)\\s?[pi]$E")
    private val RES_WORD = rx("${B}(8K|4K|UHD|QHD|FHD)$E")

    fun resolution(scan: Scan, p: Prepared) {
        scan.firstFree(RES_P)?.let { scan.add(Kind.RESOLUTION, it.groupValues[1].toInt(), it, 0.98f) }
        scan.allFree(RES_P).forEach { scan.add(Kind.NOISE, it.value, it, 1f) }
        scan.allFree(RES_WORD).forEach { m ->
            val value = when (m.groupValues[1].uppercase()) { "8K" -> 4320; "4K", "UHD" -> 2160; "QHD" -> 1440; else -> 1080 }
            if (scan.has(Kind.RESOLUTION)) scan.add(Kind.NOISE, m.value, m, 1f) else scan.add(Kind.RESOLUTION, value, m, 0.9f)
        }
    }

    private val RES_BARE = rx("${B}(2160|1080|720|480)$E")
    private val HD_WORD = rx("${B}(HD|SD)$E")

    /** Weak resolution hints, only after everything stronger has been found. */
    fun lateResolution(scan: Scan, p: Prepared) {
        if (scan.has(Kind.RESOLUTION)) return
        val technical = scan.findings.count { it.kind in TECH_KINDS }
        if (technical > 0) scan.firstFree(RES_BARE)?.let { scan.add(Kind.RESOLUTION, it.groupValues[1].toInt(), it, 0.6f); return }
        scan.firstFree(HD_WORD)?.let { m ->
            if (m.range.first > 0) scan.add(Kind.RESOLUTION, if (m.value.equals("HD", true)) 720 else 480, m, 0.4f)
        }
    }

    private fun standardHeight(h: Int) = listOf(4320, 2160, 1440, 1080, 720, 576, 480, 360, 240).minBy { kotlin.math.abs(it - h) }

    // ------------------------------------------------------------------ source / quality

    private val QUALITIES: List<Triple<String, Regex, Float>> = listOf(
        Triple("BluRay Remux", rx("${B}(BD\\s?Remux|Blu\\s?-?Ray\\s?Remux|Remux)$E"), 0.95f),
        Triple("WEB-DL", rx("${B}WEB\\s?-?\\s?(DL|HD)(Rip)?$E"), 0.95f),
        Triple("WEBRip", rx("${B}WEB\\s?-?\\s?Rip$E"), 0.95f),
        Triple("BluRay", rx("${B}(Blu\\s?-?Ray(Rip)?|BD\\s?Rip|BR\\s?Rip|BD(25|50)|BDMV)$E"), 0.95f),
        Triple("HDRip", rx("${B}HD\\s?-?\\s?Rip$E"), 0.93f),
        Triple("PreDVD", rx("${B}Pre\\s?-?\\s?DVD(\\s?Rip)?$E"), 0.93f),
        Triple("DVDScr", rx("${B}DVD\\s?-?\\s?Scr(eener)?$E"), 0.93f),
        Triple("DVDRip", rx("${B}DVD\\s?-?\\s?Rip$E"), 0.95f),
        Triple("HDTV", rx("${B}HDTV(\\s?Rip)?$E"), 0.93f),
        Triple("TVRip", rx("${B}(TV\\s?-?\\s?Rip|PDTV|SDTV|DSR(ip)?)$E"), 0.9f),
        Triple("HDCAM", rx("${B}HD\\s?-?\\s?CAM(\\s?Rip)?$E"), 0.93f),
        Triple("HDTS", rx("${B}(HD\\s?-?\\s?TS|TELESYNC|TS\\s?-?\\s?Rip)$E"), 0.9f),
        Triple("Telecine", rx("${B}(TELECINE|HD\\s?-?\\s?TC)$E"), 0.9f),
        Triple("CAM", rx("${B}CAM(\\s?-?\\s?Rip)?$E"), 0.85f),
        Triple("DVD", rx("${B}DVD(5|9|R)?$E"), 0.8f),
        Triple("WEB", rx("${B}WEB$E"), 0.75f),
    )
    private val QUALITY_TAGS: List<Pair<String, Regex>> = listOf(
        "HQ" to rx("${B}HQ$E"),
        "PROPER" to rx("${B}PROPER$E"),
        "REPACK" to rx("${B}RE\\s?PACK$E"),
        "REAL" to rx("${B}REAL$E(?=\\s(PROPER|REPACK))"),
        "INTERNAL" to rx("${B}iNTERNAL$E"),
        "LIMITED" to rx("${B}LIMITED$E"),
        "HYBRID" to rx("${B}HYBRID$E"),
        "UNTOUCHED" to rx("${B}UNTOUCHED$E"),
        "ORIGINAL" to rx("${B}(ORG|ORIGINAL)\\s?(AUDIO)?$E"),
        "CLEAN AUDIO" to rx("${B}CLEAN\\s?AUDIO$E"),
        "LINE AUDIO" to rx("${B}LINE\\s?AUDIO$E"),
        "TRUE WEB-DL" to rx("${B}TRUE$E(?=\\sWEB)"),
    )
    private val TS_ALONE = rx("${B}TS$E")

    fun quality(scan: Scan, p: Prepared) {
        var found = false
        for ((name, regex, confidence) in QUALITIES) {
            for (m in scan.allFree(regex)) {
                if (!found) { scan.add(Kind.QUALITY, name, m, confidence); found = true } else scan.add(Kind.NOISE, m.value, m, 1f)
            }
        }
        if (!found && scan.findings.any { it.kind in TECH_KINDS }) {
            scan.firstFree(TS_ALONE)?.let { if (it.range.first > 0) scan.add(Kind.QUALITY, "HDTS", it, 0.5f) }
        }
        for ((name, regex) in QUALITY_TAGS) scan.allFree(regex).forEach { scan.add(Kind.QUALITY_TAG, name, it, 0.9f) }
    }

    private val SHORT_SERVICE = rx("${B}(NF|HS|JC|iT|PCOK|PMTP|STAN|ZEE5|SONYLIV)$E(?=\\s?WEB)")

    fun service(scan: Scan, p: Prepared) {
        for ((name, regex) in Vocabulary.SERVICES_LONG) {
            scan.firstFree(regex)?.let { m -> if (m.range.first > 0) { scan.add(Kind.SERVICE, name, m, 0.85f); return } }
        }
        scan.firstFree(SHORT_SERVICE)?.let { m ->
            Vocabulary.SERVICES_SHORT[m.groupValues[1].lowercase()]?.let { scan.add(Kind.SERVICE, it, m, 0.75f) }
        }
    }

    // ------------------------------------------------------------------ codecs

    private val VIDEO_CODECS: List<Pair<String, Regex>> = listOf(
        "H.265" to rx("${B}(x\\s?265|h\\s?-?\\s?265|HEVC)$E"),
        "H.264" to rx("${B}(x\\s?264|h\\s?-?\\s?264|AVC)$E"),
        "AV1" to rx("${B}AV1$E"),
        "VP9" to rx("${B}VP9$E"),
        "XviD" to rx("${B}XviD$E"),
        "DivX" to rx("${B}DivX$E"),
        "MPEG-2" to rx("${B}MPEG\\s?-?\\s?2$E"),
        "VC-1" to rx("${B}VC\\s?-?\\s?1$E"),
    )

    fun videoCodec(scan: Scan, p: Prepared) {
        var found = false
        for ((name, regex) in VIDEO_CODECS) {
            for (m in scan.allFree(regex)) {
                if (!found) { scan.add(Kind.VIDEO_CODEC, name, m, 0.95f); found = true } else scan.add(Kind.NOISE, m.value, m, 1f)
            }
        }
    }

    private val BITS = rx("${B}(8|10|12)\\s?-?\\s?bits?$E")

    fun bitDepth(scan: Scan, p: Prepared) {
        scan.firstFree(BITS)?.let { scan.add(Kind.BIT_DEPTH, it.groupValues[1].toInt(), it, 0.9f) }
    }

    private val HDRS: List<Pair<String, Regex>> = listOf(
        "DV" to rx("${B}(DoVi|Dolby\\s?Vision|DV)$E"),
        "HDR10+" to rx("${B}HDR\\s?10\\s?(\\+|Plus)"),
        "HDR10" to rx("${B}HDR\\s?10$E"),
        "HDR" to rx("${B}HDR$E"),
        "HLG" to rx("${B}HLG$E"),
        "SDR" to rx("${B}SDR$E"),
    )

    fun hdr(scan: Scan, p: Prepared) {
        val found = mutableListOf<Pair<String, MatchResult>>()
        for ((name, regex) in HDRS) scan.allFree(regex).forEach { m ->
            // "DV" alone could be anything; trust it only among other technical words.
            if (name == "DV" && m.value.length == 2 && scan.findings.none { it.kind in TECH_KINDS }) return@forEach
            scan.claim(m.range); found += name to m
        }
        if (found.isEmpty()) return
        val names = found.map { it.first }.distinct()
        val first = found.minBy { it.second.range.first }.second
        val last = found.maxBy { it.second.range.last }.second
        scan.add(Kind.HDR, names.joinToString(" "), first.range.first..last.range.last, 0.85f, names.joinToString(" "), claim = false)
    }

    // ------------------------------------------------------------------ audio

    private val AUDIO_CODECS: List<Pair<String, Regex>> = listOf(
        "TrueHD" to rx("${B}TrueHD$E"),
        "DTS-HD MA" to rx("${B}DTS\\s?-?\\s?HD\\s?-?\\s?MA$E"),
        "DTS-HD" to rx("${B}DTS\\s?-?\\s?HD$E"),
        "DTS:X" to rx("${B}DTS\\s?-?\\s?X$E"),
        "DTS" to rx("${B}DTS$E"),
        "Atmos" to rx("${B}Atmos$E"),
        "DD+" to rx("${B}(DDP|DD\\+|E\\s?-?\\s?AC\\s?-?\\s?3|Dolby\\s?Digital\\s?Plus)"),
        "DD" to rx("${B}(DD(?![P+])|AC\\s?-?\\s?3|Dolby\\s?Digital)"),
        "AAC" to rx("${B}(HE-?)?AAC"),
        "FLAC" to rx("${B}FLAC$E"),
        "Opus" to rx("${B}Opus$E"),
        "MP3" to rx("${B}MP3$E"),
        "LPCM" to rx("${B}L?PCM$E"),
    )
    private val CHANNELS_AFTER = Regex("^\\s?([1-9])\\s?([01])(?:\\s?ch)?(?![\\d])", I)
    private val CHANNELS_ALONE = rx("${B}([257])\\s([01])$E|${B}([2-8])\\s?CH$E")
    private val BITRATE = rx("${B}(\\d{2,4})\\s?kbps$E")
    private val DUAL = rx("${B}Dual\\s?-?\\s?Audio$E|${B}DUAL$E")
    private val MULTI = rx("${B}(Multi\\s?-?\\s?Audios?|\\d\\s?Audios|Multi)$E")

    fun audio(scan: Scan, p: Prepared) {
        for ((name, regex) in AUDIO_CODECS) {
            for (m in scan.allFree(regex)) {
                var range = m.range
                var channels: String? = null
                CHANNELS_AFTER.find(scan.text.substring(m.range.last + 1))?.let { c ->
                    channels = "${c.groupValues[1]}.${c.groupValues[2]}"
                    range = m.range.first..(m.range.last + c.range.last + 1)
                }
                if (!scan.isFree(range)) continue
                scan.add(Kind.AUDIO_CODEC, name, range, 0.9f, scan.text.substring(range), channels?.let { mapOf("channels" to it) } ?: emptyMap())
            }
        }
        scan.allFree(CHANNELS_ALONE).forEach { m ->
            val channels = if (m.groupValues[3].isNotEmpty()) when (m.groupValues[3]) { "2" -> "2.0"; "6" -> "5.1"; "8" -> "7.1"; else -> "${m.groupValues[3]}ch" }
            else "${m.groupValues[1]}.${m.groupValues[2]}"
            scan.add(Kind.AUDIO_CHANNELS, channels, m, 0.8f)
        }
        scan.allFree(BITRATE).forEach { scan.add(Kind.AUDIO_BITRATE, it.groupValues[1].toInt(), it, 0.9f) }
        scan.allFree(DUAL).forEach { scan.add(Kind.DUAL_AUDIO, true, it, if (it.value.length > 4) 0.9f else 0.6f) }
        scan.allFree(MULTI).forEach { scan.add(Kind.MULTI_AUDIO, true, it, if (it.value.length > 5) 0.9f else 0.55f) }
    }

    // ------------------------------------------------------------------ languages and subtitles

    private val LANG_WORDS: String = (Vocabulary.FULL_NAMES + Vocabulary.SHORT_NAMES).sortedByDescending { it.length }.joinToString("|")
    private val SUB_WORD = "(?:subs?|subtitles?|subbed|subtitled)"
    private val LANG_SUBS = rx("$B($LANG_WORDS)\\s?-?\\s?$SUB_WORD$E")
    private val ESUBS = rx("${B}(E\\s?-?\\s?Subs?|ESubbed)$E")
    private val MSUBS = rx("${B}(M\\s?-?\\s?Subs?|Multi\\s?-?\\s?Subs?)$E")
    private val HARDSUB = rx("${B}(Hard\\s?-?\\s?Subs?|Hardcoded(\\s?Subs?)?)$E")
    private val HC = rx("${B}HC$E")

    /** "HC" alone is ambiguous: trusted only once other technical words were found. */
    fun hardsubCode(scan: Scan, p: Prepared) {
        if (scan.findings.none { it.kind in TECH_KINDS }) return
        scan.allFree(HC).forEach { m -> if (m.range.first > 0) scan.add(Kind.SUBTITLE, "hardcoded", m, 0.75f) }
    }
    private val SOFTSUB = rx("${B}Soft\\s?-?\\s?Subs?$E")
    private val NOSUB = rx("${B}(No|Without)\\s?-?\\s?Subs?$E")
    private val GENERIC_SUB = rx("$B$SUB_WORD$E")

    fun subtitles(scan: Scan, p: Prepared) {
        scan.allFree(LANG_SUBS).forEach { m ->
            Vocabulary.languageOf(m.groupValues[1])?.let { scan.add(Kind.SUBTITLE, it.language, m, 0.85f) }
        }
        scan.allFree(ESUBS).forEach { m -> Vocabulary.languageOf("english")?.let { scan.add(Kind.SUBTITLE, it.language, m, 0.85f) } }
        scan.allFree(MSUBS).forEach { scan.add(Kind.SUBTITLE, "multi", it, 0.8f) }
        scan.allFree(HARDSUB).forEach { m -> scan.add(Kind.SUBTITLE, "hardcoded", m, 0.85f) }
        scan.allFree(SOFTSUB).forEach { scan.add(Kind.SUBTITLE, "softsub", it, 0.8f) }
        scan.allFree(NOSUB).forEach { scan.add(Kind.SUBTITLE, "none", it, 0.8f) }
        scan.allFree(GENERIC_SUB).forEach { m -> if (m.range.first > 0) scan.add(Kind.SUBTITLE, "unknown", m, 0.6f) }
    }

    private val DUBBED = rx("${B}(Dubbed|Dub)$E")

    fun dubbed(scan: Scan, p: Prepared) {
        scan.allFree(DUBBED).forEach { scan.add(Kind.DUBBED, true, it, 0.8f) }
    }

    private val FULL = rx("$B(${Vocabulary.FULL_NAMES.joinToString("|")})$E")
    private val NATIVE = Regex("$B(${Vocabulary.NATIVE_NAMES.joinToString("|")})$E")
    private val GROUPED = rx("$B([\\p{L}]{2,10})((?:\\s?[+&/,-]\\s?[\\p{L}]{2,10}){1,8})$E")
    /** "Tam.Tel.Hin" (dots become spaces): runs of ≥ 2 distinct abbreviations, never at the start (that is the title). */
    private val SPACED_SHORT = rx("$B((?:${Vocabulary.SHORT_NAMES.joinToString("|")})(?:\\s(?:${Vocabulary.SHORT_NAMES.joinToString("|")})){1,7})$E")
    private val SHORT_WITH_CONTEXT = rx("$B(${Vocabulary.SHORT_NAMES.joinToString("|")})(?=\\s(?:dub|dubbed|audio|aud|version|ver)$E)")

    fun languages(scan: Scan, p: Prepared) {
        // Groups like "Tam+Tel+Hin" or "Tamil-Telugu-Hindi" (abbreviations are trusted here).
        scan.allFree(GROUPED).forEach { m ->
            val words = m.value.split(Regex("\\s?[+&/,-]\\s?")).map { it.trim() }
            val entries = words.map { Vocabulary.languageOf(it) }
            if (entries.size >= 2 && entries.all { it != null }) {
                var cursor = m.range.first
                for ((word, entry) in words.zip(entries)) {
                    val start = scan.text.indexOf(word, cursor, ignoreCase = true)
                    val range = start until start + word.length
                    cursor = range.last + 1
                    scan.add(Kind.LANGUAGE, entry!!.language, range, if (word.length > 4) 0.85f else 0.75f, word)
                }
                scan.claim(m.range)
            }
        }
        scan.allFree(SPACED_SHORT).forEach { m ->
            if (m.range.first == 0) return@forEach
            val words = m.value.split(' ').filter { it.isNotEmpty() }
            val entries = words.map { Vocabulary.languageOf(it) }
            if (entries.any { it == null } || entries.map { it!!.language }.distinct().size != entries.size) return@forEach
            var cursor = m.range.first
            for ((word, entry) in words.zip(entries)) {
                val start = scan.text.indexOf(word, cursor, ignoreCase = true)
                val range = start until start + word.length
                cursor = range.last + 1
                scan.add(Kind.LANGUAGE, entry!!.language, range, 0.75f, word)
            }
            scan.claim(m.range)
        }
        scan.allFree(NATIVE).forEach { m -> Vocabulary.languageOf(m.value)?.let { scan.add(Kind.LANGUAGE, it.language, m, 0.9f) } }
        scan.allFree(FULL).forEach { m -> Vocabulary.languageOf(m.value)?.let { scan.add(Kind.LANGUAGE, it.language, m, 0.85f) } }
        scan.allFree(SHORT_WITH_CONTEXT).forEach { m -> Vocabulary.languageOf(m.value)?.let { scan.add(Kind.LANGUAGE, it.language, m, 0.75f) } }
    }

    // ------------------------------------------------------------------ editions, flags, parts

    private val EDITIONS: List<Pair<String, Regex>> = listOf(
        "Director's Cut" to rx("${B}Director'?s?\\s?Cut$E"),
        "Extended" to rx("${B}Extended(\\s?(Cut|Edition|Version))?$E"),
        "Theatrical" to rx("${B}Theatrical(\\s?(Cut|Version))?$E"),
        "Uncut" to rx("${B}Uncut$E"),
        "Unrated" to rx("${B}Unrated$E"),
        "Remastered" to rx("${B}(\\d{1,2}K\\s?)?Remaster(ed)?$E"),
        "IMAX" to rx("${B}IMAX$E"),
        "Criterion" to rx("${B}Criterion$E"),
        "Special Edition" to rx("${B}Special\\s?Edition$E"),
        "Anniversary Edition" to rx("${B}(\\d{1,3}(th)?\\s?)?Anniversary(\\s?Edition)?$E"),
        "Final Cut" to rx("${B}Final\\s?Cut$E"),
        "Open Matte" to rx("${B}Open\\s?Matte$E"),
    )

    fun edition(scan: Scan, p: Prepared) {
        for ((name, regex) in EDITIONS) scan.firstFree(regex)?.let { m ->
            if (m.range.first > 0) { scan.add(Kind.EDITION, name, m, 0.85f); return }
        }
    }

    private val FLAGS: List<Pair<String, Regex>> = listOf(
        "SAMPLE" to rx("${B}Sample$E"),
        "TRAILER" to rx("${B}(Official\\s)?(Trailer|Teaser)$E"),
        "THREE_D" to rx("${B}(3D|H\\s?-?\\s?SBS|H\\s?-?\\s?OU|Half\\s?-?\\s?SBS)$E"),
        "COMPLETE" to rx("${B}Complete(\\s(Series|Season|Collection))?$E"),
    )

    fun flags(scan: Scan, p: Prepared) {
        for ((name, regex) in FLAGS) scan.allFree(regex).forEach { m -> if (m.range.first > 0) scan.add(Kind.FLAG, name, m, 0.85f) }
    }

    private val DISC = rx("${B}(?:CD|Disc|Disk|DVD)\\s?(\\d{1,2})$E")
    private val PART_WORD = rx("${B}(?:Part|Pt)\\s?(\\d{1,2})$E")

    fun part(scan: Scan, p: Prepared) {
        scan.firstFree(DISC)?.let { scan.add(Kind.PART, it.groupValues[1].toInt(), it, 0.9f) }
        // "Part 2" is often a title ("Dune Part Two", "Kill Bill Part 2"): accept only at the end.
        scan.firstFree(PART_WORD)?.let { m ->
            if (scan.freeText(m.range.last + 1, scan.text.length).isEmpty() && !scan.has(Kind.PART)) {
                scan.add(Kind.PART, m.groupValues[1].toInt(), m, 0.6f)
            }
        }
    }

    // ------------------------------------------------------------------ years

    private val maxYear = Year.now().value + 1
    private val YEAR = Regex("$B(19[2-9]\\d|20\\d{2})$E")

    /** Collects candidates only; the parser decides which one is the year (titles contain years too). */
    fun years(scan: Scan, p: Prepared) {
        scan.allFree(YEAR).forEach { m ->
            val y = m.value.toInt()
            if (y <= maxYear) scan.add(Kind.YEAR_CANDIDATE, y, m, 0f, claim = false)
        }
    }

    // ------------------------------------------------------------------ release group

    private val TRAILING_GROUP = Regex("-\\s?([A-Za-z0-9][A-Za-z0-9._]{1,24})\\s*$")

    fun releaseGroup(scan: Scan, p: Prepared) {
        p.leadingGroup?.let { scan.findings += Finding(Kind.GROUP, it, -1..-1, 0.9f, it); return }
        if (!p.hyphenSeparated) {
            val m = TRAILING_GROUP.find(scan.text)
            if (m != null && scan.isFree(m.groups[1]!!.range) && scan.findings.any { it.kind in TECH_KINDS }) {
                val candidate = m.groupValues[1]
                val technical = candidate.all(Char::isDigit) || Vocabulary.languageOf(candidate) != null ||
                    YEAR.matches(candidate) || RES_P.matches(candidate)
                if (!technical) { scan.add(Kind.GROUP, candidate, m, 0.8f); return }
            }
        }
        p.trailingBracketGroup?.let { scan.findings += Finding(Kind.GROUP, it, -1..-1, 0.6f, it) }
    }

    /** Findings that mark the technical part of a name (used for "is this context technical?"). */
    val TECH_KINDS = setOf(
        Kind.RESOLUTION, Kind.QUALITY, Kind.VIDEO_CODEC, Kind.AUDIO_CODEC, Kind.HDR, Kind.BIT_DEPTH, Kind.SERVICE,
        Kind.SEASON, Kind.EPISODE,
    )
}
