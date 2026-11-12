package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.LocalMangaStyle
import dev.yourstream.ui.core.LocalReducedMotion
import dev.yourstream.ui.core.Manga
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.screens.IndexingStatus
import dev.yourstream.ui.screens.R
import java.text.NumberFormat

/**
 * What indexing is doing, floating above the navigation on every tab while it runs: files found,
 * a real progress bar (Telegram's estimate of the chats' size), speed and time left. Tap: Library
 * status. With reduced motion it appears without sliding and its icon does not spin.
 */
@Composable
fun IndexingPill(status: IndexingStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = status.active, modifier = modifier,
        enter = if (reduced) fadeIn() else slideInVertically { it } + fadeIn(),
        exit = if (reduced) fadeOut() else slideOutVertically { it } + fadeOut(),
    ) {
        val manga = LocalMangaStyle.current
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(18.dp)
        val angle = if (reduced) null else {
            val spin = rememberInfiniteTransition(label = "indexing-spin")
            spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        }
        val number = remember { NumberFormat.getIntegerInstance() }
        val surfaceModifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()
            .let { if (manga) it.padding(end = 3.dp, bottom = 3.dp).inkPanel(shape, shadow = 3.dp, border = 2.dp) else it }
            .clip(shape).clickable(onClick = onClick).testTag("indexing-pill")
        Surface(shape = shape, color = if (manga) colors.surfaceContainer else colors.inverseSurface,
            contentColor = if (manga) colors.onSurface else colors.inverseOnSurface, shadowElevation = if (manga) 0.dp else 6.dp, modifier = surfaceModifier) {
            Column {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = angle?.value ?: 0f })
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        val title = pluralStringResource(R.plurals.indexing_pill_found, status.found, number.format(status.found))
                        if (manga) Text(title.uppercase(), fontFamily = Manga.Lettering, fontSize = 15.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(indexingDetail(status), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("indexing-detail"))
                    }
                    status.fraction?.let { f -> Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelLarge) }
                }
                val accent = if (manga) colors.tertiary else colors.inversePrimary
                val track = if (manga) colors.onSurface.copy(alpha = 0.12f) else colors.inverseOnSurface.copy(alpha = 0.2f)
                val f = status.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = track, drawStopIndicator = {}, gapSize = 0.dp)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = track)
            }
        }
    }
}

/** "About 4 min left · 140/s · 2 chats · 1 waiting": what matters most first, for narrow phones. */
@Composable
private fun indexingDetail(s: IndexingStatus): String {
    val parts = mutableListOf<String>()
    s.etaSeconds?.let { parts += stringResource(R.string.indexing_pill_eta, durationLabel(it)) }
    s.perSecond?.let { parts += stringResource(R.string.indexing_pill_rate, it.toInt()) }
    parts += pluralStringResource(R.plurals.indexing_pill_chats, s.chats.coerceAtLeast(1), s.chats.coerceAtLeast(1))
    if (s.queued > 0) parts += pluralStringResource(R.plurals.indexing_pill_waiting, s.queued, s.queued)
    return parts.joinToString(" · ")
}

/** 40 → "1 min", 3_900 → "1 h 5 min" (short, rounded up to whole minutes). */
internal fun durationLabel(seconds: Long): String {
    val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
