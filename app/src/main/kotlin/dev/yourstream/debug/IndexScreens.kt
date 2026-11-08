package dev.yourstream.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexedMedia
import dev.yourstream.media.api.model.MediaSummary
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.filename.MediaFilenameParser
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Stage-1 debug UI for the media index. Plain on purpose.

/** Index controls shown on a chat's detail page. */
@Composable
fun ChatIndexControls(vm: IndexDebugViewModel, chatId: Long) {
    val status by remember(chatId) { vm.chatStatus(chatId) }.collectAsStateWithLifecycle(null)
    HorizontalDivider()
    Text("Media index", modifier = Modifier.padding(top = 8.dp))
    Text(status?.line() ?: "Not indexed yet", fontSize = 12.sp)
    status?.progress?.let { Text(it.line(), fontSize = 12.sp) }
    status?.lastError?.let { Text("Last error: $it", fontSize = 12.sp, color = Color(0xFFB00020)) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { vm.start(chatId, IndexMode.INCREMENTAL) }) { Text(if (status == null) "Start indexing" else "Index new") }
        TextButton(onClick = { vm.start(chatId, IndexMode.FULL_RESCAN) }) { Text("Full rescan") }
        TextButton(onClick = { vm.start(chatId, IndexMode.VERIFY) }) { Text("Check deleted") }
        TextButton(onClick = { vm.cancel(chatId) }) { Text("Cancel") }
        if (status != null) TextButton(onClick = { vm.disable(chatId, purge = true) }) { Text("Remove from index") }
    }
}

@Composable
fun IndexPane(vm: IndexDebugViewModel) {
    val details by vm.details.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    message?.let { Text(it, fontSize = 12.sp, modifier = Modifier.clickable { vm.dismissMessage() }) }
    val current = details
    if (current != null) {
        BackHandler { vm.closeDetails() }
        MediaDetailsPane(current.media, current.duplicates, onBack = vm::closeDetails, onOpen = vm::open)
    } else {
        SearchPane(vm)
    }
}

/** Type any name and see what the parser makes of it (no indexing involved). */
@Composable
private fun ParserPlayground() {
    var input by remember { mutableStateOf("Doraemon.S03E12.Tamil.720p.mkv") }
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide parser playground" else "Parser playground") }
    if (!open) return
    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val m = remember(input) { MediaFilenameParser.parse(input) }
    val lines = buildList {
        add("cleaned: ${m.cleanedName}  ext: ${m.extension ?: "—"}  kind: ${m.kind.value} (${pct(m.kind.confidence)})  overall: ${pct(m.confidence)}")
        m.title?.let { add("title: ${it.value} (${pct(it.confidence)})  key: ${m.normalizedTitle}") }
        m.year?.let { add("year: ${it.value} (${pct(it.confidence)})") }
        m.season?.let { add("season: ${it.value}${m.seasonEnd?.let { e -> "-$e" } ?: ""} (${pct(it.confidence)})") }
        m.episode?.let { add("episode: ${it.value}${m.episodeEnd?.let { e -> "-$e" } ?: ""} (${pct(it.confidence)})") }
        m.absoluteEpisode?.let { add("absolute episode: ${it.value} (${pct(it.confidence)})") }
        m.resolution?.let { add("resolution: ${it.value}p (${pct(it.confidence)})") }
        m.quality?.let { add("quality: ${it.value} (${pct(it.confidence)})") }
        m.videoCodec?.let { add("codec: ${it.value}") }
        m.audio?.let { add("audio: ${it.codecs} ${it.channels ?: ""} dual=${it.isDualAudio}") }
        if (m.languages.isNotEmpty()) add("languages: " + m.languages.joinToString { "${it.value.name} (${pct(it.confidence)})" })
        m.subtitles?.let { add("subtitles: ${it.languages.map { l -> l.code }} hardcoded=${it.hardcoded}") }
        m.releaseGroup?.let { add("group: ${it.value}") }
        if (m.warnings.isNotEmpty()) add("warnings: ${m.warnings}")
    }
    lines.forEach { Text(it, fontSize = 11.sp) }
    HorizontalDivider()
}

private fun pct(f: Float) = String.format(Locale.US, "%.2f", f)

private fun String?.withConfidence(p: dev.yourstream.media.api.model.ParsedMedia?, field: String): String? {
    if (this == null) return null
    val c = p?.fieldConfidence?.get(field) ?: return this
    return "$this  (${pct(c)})"
}

@Composable
private fun SearchPane(vm: IndexDebugViewModel) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val count by vm.resultCount.collectAsStateWithLifecycle()
    val items = vm.results.collectAsLazyPagingItems()
    var text by remember { mutableStateOf(query.text) }

    Column(Modifier.fillMaxSize()) {
        Row {
            Text("Indexed chats (${chats.size}) — tap to filter search", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = vm::runAll) { Text("Index all") }
        }
        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            chats.forEach { chat ->
                val selected = query.chatId == chat.chatId
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { vm.setChatFilter(if (selected) null else chat.chatId) }
                        .padding(vertical = 4.dp),
                ) {
                    Text((if (selected) "[x] " else "") + chat.title, fontSize = 13.sp)
                    Text(chat.line(), fontSize = 11.sp)
                    chat.progress?.let { Text(it.line(), fontSize = 11.sp) }
                    chat.lastError?.let { Text("error: $it", fontSize = 11.sp, color = Color(0xFFB00020)) }
                }
            }
            if (chats.isEmpty()) Text("No chats indexed. Open a chat in the Telegram tab and tap Start indexing.", fontSize = 12.sp)
        }
        HorizontalDivider()
        ParserPlayground()
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.setText(it) },
            label = { Text("Search file names, captions, titles") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val type = query.types.firstOrNull()
            (listOf<MediaType?>(null) + listOf(MediaType.VIDEO, MediaType.AUDIO, MediaType.SUBTITLE)).forEach { t ->
                val name = t?.name ?: "ALL"
                TextButton(onClick = { vm.setType(t) }) { Text(if (t == type) "[$name]" else name, fontSize = 12.sp) }
            }
            TextButton(onClick = vm::toggleDeleted) { Text(if (query.includeDeleted) "[deleted]" else "deleted", fontSize = 12.sp) }
            TextButton(onClick = vm::toggleDuplicates) { Text(if (query.includeDuplicates) "[dupes]" else "dupes", fontSize = 12.sp) }
        }
        Text("$count results" + (query.chatId?.let { " in chat $it" } ?: ""), fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                items[index]?.let { MediaRow(it) { vm.open(it.id) } }
            }
            if (items.loadState.append is LoadState.Loading) item { Text("Loading…", modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun MediaRow(media: MediaSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp)) {
        Text(media.fileName.ifEmpty { media.parsedTitle ?: "(no name)" }, fontSize = 13.sp)
        Text(
            listOfNotNull(
                media.mediaType.name,
                bytes(media.sizeBytes),
                media.parsedTitle?.let { "“$it”" },
                media.season?.let { s -> "S$s" + (media.episode?.let { "E$it" } ?: "") } ?: media.episode?.let { "E$it" }
                    ?: media.absoluteEpisode?.let { "#$it" },
                media.resolution?.let { "${it}p" },
                media.durationSeconds?.let { duration(it) },
                if (media.isDuplicate) "duplicate" else null,
                if (media.isDeleted) "DELETED" else null,
                "msg ${media.messageId}",
            ).joinToString(" · "),
            fontSize = 11.sp,
        )
    }
    HorizontalDivider()
}

@Composable
private fun MediaDetailsPane(media: IndexedMedia, duplicates: List<MediaSummary>, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("< Back to search") }
        val p = media.parsed
        listOf(
            "Record id" to media.id.toString(),
            "File name (original)" to media.fileName.ifEmpty { "(Telegram sent no name)" },
            "Media type" to media.mediaType.name,
            "Sent as" to media.contentType.name,
            "MIME" to media.mimeType.ifBlank { null },
            "Extension" to media.extension,
            "Size" to "${bytes(media.sizeBytes)} (${media.sizeBytes} B)",
            "Duration" to media.durationSeconds?.let { duration(it) },
            "Dimensions" to if (media.width != null && media.height != null) "${media.width}×${media.height}" else null,
            "Video codec" to media.videoCodec,
            "Audio codecs" to media.audioCodecs.joinToString().ifBlank { null },
            "Streamable" to media.supportsStreaming.toString(),
            "Chat" to "${media.chatTitle ?: "?"} (${media.chatId})",
            "Message id" to media.messageId.toString(),
            "Album id" to media.albumId.takeIf { it != 0L }?.toString(),
            "Message date" to date(media.messageDate * 1000),
            "Edited" to media.editDate.takeIf { it > 0 }?.let { date(it * 1000) },
            "File id (session)" to media.fileId.toString(),
            "Remote unique id" to media.fileUniqueId,
            "Remote file id" to media.remoteFileId,
            "Download" to "${media.downloadState} (${bytes(media.downloadedBytes)})",
            "Local path" to media.localPath,
            "Duplicate of" to media.canonicalMediaId?.let { "#$it (${media.duplicateKind})" },
            "Deleted" to if (media.isDeleted) "yes, ${media.deletedAt?.let(::date)}" else "no",
            "Indexed" to date(media.indexedAt),
            "Updated" to date(media.updatedAt),
            "Caption" to media.caption.ifBlank { null },
            "— Parsed (from the original name) —" to "",
            "Kind" to p?.kind,
            "Title" to p?.title.withConfidence(p, "title"),
            "Normalized title" to p?.normalizedTitle,
            "Alternative title" to p?.alternativeTitle,
            "Year" to p?.year?.toString().withConfidence(p, "year"),
            "Season" to p?.season?.let { s -> "S$s" + (p.seasonEnd?.let { "–S$it" } ?: "") }.withConfidence(p, "season"),
            "Episode" to p?.episode?.let { e -> "E$e" + (p.episodeEnd?.let { "–E$it" } ?: "") }.withConfidence(p, "episode"),
            "Absolute episode" to p?.absoluteEpisode?.toString().withConfidence(p, "absoluteEpisode"),
            "Air date" to p?.airDate,
            "Resolution" to p?.resolution?.let { "${it}p" }.withConfidence(p, "resolution"),
            "Quality" to p?.quality.withConfidence(p, "quality"),
            "Quality tags" to p?.qualityTags?.joinToString()?.ifBlank { null },
            "Service" to p?.streamingService,
            "Codec (name)" to p?.videoCodec.withConfidence(p, "videoCodec"),
            "Bit depth" to p?.bitDepth?.toString(),
            "HDR" to p?.hdr,
            "Audio" to p?.let { listOfNotNull(it.audioCodecs.joinToString().ifBlank { null }, it.audioChannels, it.audioBitrateKbps?.let { b -> "${b}kbps" }, if (it.isDualAudio) "dual" else null, if (it.isMultiAudio) "multi" else null).joinToString(" ").ifBlank { null } },
            "Languages" to p?.languages?.joinToString()?.ifBlank { null }?.let { if (p.isDubbed) "$it (dubbed)" else it }.withConfidence(p, "language"),
            "Subtitles" to p?.let { listOfNotNull(it.subtitleLanguages.joinToString().ifBlank { null }, it.subtitlesHardcoded?.let { h -> if (h) "hardcoded" else "soft" }).joinToString(" ").ifBlank { null } },
            "Edition" to p?.edition,
            "Release group" to p?.releaseGroup.withConfidence(p, "releaseGroup"),
            "Part" to p?.partNumber?.toString(),
            "Flags" to p?.flags?.joinToString()?.ifBlank { null },
            "Overall confidence" to p?.confidence?.let { String.format(Locale.US, "%.2f", it) },
            "Warnings" to p?.warnings?.joinToString("; ")?.ifBlank { null },
            "Parser version" to p?.parserVersion?.toString(),
        ).forEach { (label, value) -> Text("$label: ${value ?: "—"}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp)) }

        if (duplicates.isNotEmpty()) {
            HorizontalDivider()
            Text("Same file elsewhere (${duplicates.size})", modifier = Modifier.padding(top = 8.dp))
            duplicates.forEach { MediaRow(it) { onOpen(it.id) } }
        }
    }
}

private fun ChatIndexStatus.line(): String =
    "$state · ${mediaCount} media" + (if (deletedCount > 0) " · $deletedCount deleted" else "") +
        (if (backfillComplete) " · history complete" else " · history in progress") +
        (lastIndexedAt?.let { " · indexed ${date(it)}" } ?: "") +
        (lastVerifiedAt?.let { " · checked ${date(it)}" } ?: "")

private fun dev.yourstream.media.api.model.IndexProgress.line(): String =
    "running $mode/$phase${filter?.let { " ($it)" } ?: ""}: $pagesScanned pages, $messagesSeen messages, " +
        "+$mediaAdded ~$mediaUpdated -$mediaDeleted" + (if (estimatedTotal >= 0) " of ~$estimatedTotal" else "")

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

private fun duration(seconds: Int) = String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)

private fun date(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
