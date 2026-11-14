package dev.yourstream.ui.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A gently pulsing placeholder surface; still when motion is reduced ([LocalReducedMotion]). */
fun Modifier.skeleton(): Modifier = composed {
    val alpha = if (LocalInspectionMode.current || LocalReducedMotion.current) 0.6f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val a by t.animateFloat(0.45f, 0.85f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-alpha")
        a
    }
    this.graphicsLayer { this.alpha = alpha }.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)
}

@Composable
fun SkeletonCard(style: CardStyle = CardStyle.POSTER, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (style == CardStyle.WIDE) 16f / 9f else 2f / 3f).skeleton())
        if (style == CardStyle.POSTER) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.8f).height(14.dp).skeleton())
            Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(12.dp).skeleton())
        }
    }
}

@Composable
fun SkeletonRow(title: String? = null, style: CardStyle = CardStyle.POSTER, modifier: Modifier = Modifier) {
    val d = LocalDimens.current
    val label = stringResource(R.string.ui_loading)
    Column(modifier.fillMaxWidth().testTag(StateTags.LOADING).semantics { contentDescription = label }) {
        if (title != null) SectionHeader(title) else Box(Modifier.padding(horizontal = d.gutter, vertical = 12.dp).width(160.dp).height(22.dp).skeleton())
        Row(Modifier.padding(horizontal = d.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(d.cardGap)) {
            repeat(6) { SkeletonCard(style, Modifier.width(if (style == CardStyle.WIDE) d.wideCardWidth else d.posterWidth)) }
        }
    }
}

@Composable
fun SkeletonGrid(style: CardStyle = CardStyle.POSTER, modifier: Modifier = Modifier) {
    val d = LocalDimens.current
    val label = stringResource(R.string.ui_loading)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (style == CardStyle.WIDE) d.wideCardWidth else d.posterWidth),
        modifier = modifier.fillMaxSize().testTag(StateTags.LOADING).semantics { contentDescription = label },
        contentPadding = PaddingValues(d.gutter), horizontalArrangement = Arrangement.spacedBy(d.cardGap), verticalArrangement = Arrangement.spacedBy(d.rowGap),
        userScrollEnabled = false,
    ) { items(18) { SkeletonCard(style) } }
}

@Composable
fun SkeletonHero(modifier: Modifier = Modifier) {
    val d = LocalDimens.current
    Box(modifier.fillMaxWidth().height(d.heroHeight).padding(horizontal = d.gutter).skeleton())
}
