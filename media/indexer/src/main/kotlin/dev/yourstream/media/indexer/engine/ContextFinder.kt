package dev.yourstream.media.indexer.engine

import dev.yourstream.core.common.AppResult
import dev.yourstream.media.filename.MediaFilenameParser
import dev.yourstream.telegram.api.model.ChatPost

/**
 * Finds the post above a file, for files whose own name and caption carry no title.
 *
 * Movie channels usually post the title first (a poster with a caption, or a text post), then the
 * files, captioned only "Tel+Hin 450MB" or not at all. From the file the chat's history is read
 * towards older messages: the group's other files are skipped, promotions ("Join @…", links) are
 * not titles, and the walk stops after [maxSteps] messages or [maxAgeSeconds] before the file.
 * The first text that names a title is the file's context.
 *
 * A page of history is kept and reused, so one request serves the neighbouring files too. After
 * a failed request every lookup answers null ("unknown, try another time") for the rest of the
 * run, instead of hammering Telegram.
 */
class ContextFinder(
    private val history: suspend (chatId: Long, fromMessageId: Long) -> AppResult<List<ChatPost>>,
    private val maxSteps: Int = 12,
    private val maxAgeSeconds: Long = 6 * 3_600,
) {
    /** The last page read, newest first. */
    private var window: List<ChatPost> = emptyList()
    private var windowChat = 0L
    private var failed = false

    /** The post above message [messageId] (sent at [date]): its text, "" when there is none, null when unknown. */
    suspend fun find(chatId: Long, messageId: Long, date: Long): String? {
        if (failed) return null
        var page = covering(chatId, messageId) ?: return null
        var i = page.indexOfFirst { it.messageId < messageId }.let { if (it < 0) page.size else it }
        var steps = 0
        while (true) {
            if (i >= page.size) {
                val oldest = page.lastOrNull()?.messageId ?: return ""
                page = load(chatId, oldest)?.filter { it.messageId < oldest } ?: return null
                if (page.isEmpty()) return ""
                i = 0
            }
            val post = page[i++]
            if (date - post.date > maxAgeSeconds || ++steps > maxSteps) return ""
            if (!post.hasFile && namesTitle(post.text)) return post.text.trim()
        }
    }

    /** The kept page if it reaches [messageId], else a new page read from there. */
    private suspend fun covering(chatId: Long, messageId: Long): List<ChatPost>? {
        val w = window
        if (windowChat == chatId && w.isNotEmpty() && w.first().messageId >= messageId && w.last().messageId < messageId) return w
        return load(chatId, messageId)
    }

    private suspend fun load(chatId: Long, fromMessageId: Long): List<ChatPost>? =
        when (val r = runCatching { history(chatId, fromMessageId) }.getOrElse { AppResult.Err(dev.yourstream.core.common.AppError.Network()) }) {
            is AppResult.Ok -> r.value.sortedByDescending { it.messageId }.also { window = it; windowChat = chatId }
            is AppResult.Err -> { failed = true; null }
        }

    companion object {
        private val PROMO = Regex("(?i)\\b(join|request|subscribe|backup|follow|click here|how to)\\b|t\\.me/|https?://|@\\w")

        /** Whether a post's text names a title: a line that is not a promotion, a tag list or a link (pure; tested). */
        fun namesTitle(text: String): Boolean {
            val line = text.lineSequence().map { it.trim() }
                .firstOrNull { it.count(Char::isLetter) >= 2 && !it.startsWith("#") && !PROMO.containsMatchIn(it) } ?: return false
            val title = MediaFilenameParser.parseText(line).title?.value ?: return false
            return title.count(Char::isLetter) >= 2 && !MediaFilenameParser.isTagsOnly(title, fromCaption = true, otherLanguages = 1)
        }
    }
}
