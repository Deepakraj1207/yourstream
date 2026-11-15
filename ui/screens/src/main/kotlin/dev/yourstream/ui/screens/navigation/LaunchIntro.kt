package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.Manga
import dev.yourstream.ui.core.rememberSystemReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ink, paper and stamp red of the app icon: the intro continues the system launch screen exactly. */
private val Ink = Color(0xFF0E0E0E)
private val Paper = Color(0xFFF4EFE4)
private val Stamp = Color(0xFFD7263D)

/**
 * The opening logo, after Android's launch screen (which shows the same speech bubble on the same
 * ink, at the same size, so the hand-over is invisible): the bubble pops, "YourStream" in comic
 * lettering slides up under it, and the whole picture fades into the app. About a second; a tap
 * skips it. With reduced motion (system animations off) nothing moves: the picture is shown
 * briefly and fades.
 *
 * [logo] is the app icon's foreground, drawn at the size Android draws a launch icon without a
 * background (288 dp), so it lines up with the system launch screen.
 */
@Composable
fun LaunchIntro(logo: Painter, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberSystemReducedMotion()
    val scale = remember { Animatable(1f) }
    val lift = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val finished = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        scope.launch {
            fade.animateTo(0f, tween(if (reduced) 120 else 260, easing = FastOutSlowInEasing))
            onDone()
        }
    }
    LaunchedEffect(Unit) {
        if (reduced) {
            word.snapTo(1f); lift.snapTo(1f); scale.snapTo(0.72f)
            delay(450)
        } else {
            // Pop: a small squash, then settle smaller and higher to make room for the name.
            scale.animateTo(0.86f, tween(110, easing = FastOutSlowInEasing))
            launch { scale.animateTo(0.72f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
            launch { lift.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
            delay(140)
            word.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            delay(320)
        }
        finish()
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
            .semantics { contentDescription = "YourStream" }
            .testTag("launch-intro"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                logo, contentDescription = null,
                modifier = Modifier.size(288.dp)
                    .offset(y = (-36).dp * lift.value)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            )
            Text(
                "YourStream",
                style = TextStyle(
                    fontFamily = Manga.Lettering, fontSize = 44.sp, letterSpacing = 1.5.sp, color = Paper,
                    shadow = Shadow(Stamp, Offset(5f, 5f), 0f),
                ),
                modifier = Modifier
                    .offset(y = (-84).dp + 24.dp * (1f - word.value))
                    .graphicsLayer { alpha = word.value },
            )
        }
    }
}
