package dev.yourstream.ui.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The featured title on Home: what it is and the two things you can do with it. */
@Immutable
data class HeroModel(
    val key: String,
    val title: String,
    /** "Continue watching", "New in your library". */
    val eyebrow: String,
    val meta: String?,
    val posterUrl: String?,
    val progress: Float?,
    val primaryLabel: String,
    val secondaryLabel: String,
)

/**
 * Full-width banner: blurred-feel poster backdrop with a scrim, the poster itself, title, meta and
 * actions. On TV the primary button takes focus first ([primaryFocus]).
 */
@Composable
fun HeroBanner(
    model: HeroModel,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryFocus: FocusRequester? = null,
) {
    val d = LocalDimens.current
    val compact = LocalWindowLayout.current == WindowLayout.COMPACT
    Box(modifier.fillMaxWidth().height(d.heroHeight).padding(horizontal = d.gutter).clip(MaterialTheme.shapes.large).testTag("hero")) {
        Artwork(model.posterUrl, model.title, Modifier.fillMaxSize(), showInitials = false)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.65f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.15f))))
        Row(Modifier.fillMaxSize().padding(if (compact) 16.dp else 32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!compact) {
                Artwork(model.posterUrl, model.title, Modifier.fillMaxHeight(0.9f).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium))
            }
            Column(Modifier.weight(1f).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(model.title, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                model.meta?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1) }
                model.progress?.let { ProgressLine(it, Modifier.widthIn(max = 280.dp).fillMaxWidth()) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPrimary, modifier = Modifier.then(if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("hero-primary")) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(model.primaryLabel, Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = onSecondary, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag("hero-secondary")) { Text(model.secondaryLabel) }
                }
            }
        }
    }
}
