package dev.yourstream.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ProgressLine
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.R
import dev.yourstream.watch.core.WatchStats
import dev.yourstream.watch.engine.WatchStatistics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(stats: WatchStatistics) : ViewModel() {
    val state: StateFlow<UiState<WatchStats>> = stats.stats()
        .map<WatchStats, UiState<WatchStats>> { if (it.isEmpty) UiState.Empty(EmptyKind.NO_HISTORY) else UiState.Content(it) }
        .catch { emit(UiState.Error(it.toUiError())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
}

@Composable
fun StatsDestination(onBack: () -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    StatsScreen(state, onBack)
}

/** "1 h 25 min", "45 min", "2 days 3 h". */
@Composable
fun durationLabel(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours >= 48 -> stringResource(R.string.duration_days_hours, hours / 24, hours % 24)
        hours >= 1 -> stringResource(R.string.duration_hours_minutes, hours, minutes % 60)
        else -> stringResource(R.string.duration_minutes, minutes)
    }
}

@Composable
fun StatsScreen(state: UiState<WatchStats>, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.stats_title), onBack = onBack) {
        StateContent(state) { s ->
            LazyColumn(
                Modifier.widthIn(max = 900.dp).testTag("stats"),
                contentPadding = PaddingValues(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // Two equal columns (four on wide screens), so the numbers line up.
                    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                        { m -> Tile(stringResource(R.string.stats_this_week), durationLabel(s.last7DaysMs), trend(s), "stat-week", m) },
                        { m -> Tile(stringResource(R.string.stats_total), durationLabel(s.totalMs), pluralStringResource(R.plurals.stats_sessions, s.sessions, s.sessions), "stat-total", m) },
                        { m -> Tile(stringResource(R.string.stats_finished), pluralStringResource(R.plurals.stats_movies, s.moviesFinished, s.moviesFinished),
                            pluralStringResource(R.plurals.stats_episodes, s.episodesFinished, s.episodesFinished), "stat-finished", m) },
                        { m -> Tile(stringResource(R.string.stats_streak), pluralStringResource(R.plurals.stats_days, s.currentStreakDays, s.currentStreakDays),
                            stringResource(R.string.stats_longest, pluralStringResource(R.plurals.stats_days, s.longestStreakDays, s.longestStreakDays)), "stat-streak", m) },
                    )
                    val perRow = if (dev.yourstream.ui.core.LocalWindowLayout.current == dev.yourstream.ui.core.WindowLayout.COMPACT) 2 else 4
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tiles.chunked(perRow).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                            }
                        }
                    }
                }
                item { Daily(s) }
                if (s.topLanguages.isNotEmpty()) item { Shares(stringResource(R.string.stats_languages), s.topLanguages.map { LanguageCatalog.displayName(it.key) to it.fraction }, "stat-languages") }
                if (s.topGenres.isNotEmpty()) item { Shares(stringResource(R.string.stats_genres), s.topGenres.map { it.key.replaceFirstChar(Char::titlecase) to it.fraction }, "stat-genres") }
                item {
                    Text(
                        listOfNotNull(
                            pluralStringResource(R.plurals.stats_titles, s.titlesWatched, s.titlesWatched),
                            stringResource(R.string.stats_average, durationLabel(s.averageSessionMs)),
                            s.busiestHour?.let { stringResource(R.string.stats_busiest_hour, "%02d:00".format(it)) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Text(stringResource(R.string.stats_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp).testTag("stats-privacy"))
                }
            }
        }
    }
}

@Composable
private fun trend(s: WatchStats): String = when {
    s.previous7DaysMs == 0L -> stringResource(R.string.stats_trend_new)
    else -> {
        val pct = ((s.last7DaysMs - s.previous7DaysMs) * 100 / s.previous7DaysMs).toInt()
        if (pct >= 0) stringResource(R.string.stats_trend_up, pct) else stringResource(R.string.stats_trend_down, -pct)
    }
}

@Composable
private fun Tile(label: String, value: String, detail: String, tag: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.testTag(tag)) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Last 14 days as bars, today on the right. Described in words for screen readers. */
@Composable
private fun Daily(s: WatchStats) {
    val max = s.daily.maxOf { it.watchedMs }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val days = s.daily.count { it.watchedMs > 0 }
    val description = pluralStringResource(R.plurals.stats_daily_description, days, days, s.daily.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stats_daily), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Canvas(Modifier.fillMaxWidth().height(120.dp).testTag("stat-daily").semantics { contentDescription = description }) {
            val n = s.daily.size
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            s.daily.forEachIndexed { i, d ->
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
                val h = size.height * d.watchedMs / max
                if (h > 0) drawRoundRect(primary, Offset(x, size.height - h), Size(w, h), CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val first = s.daily.first().day
            Text(first.dayOfWeek.getDisplayName(TextStyle.SHORT, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) + " " + first.dayOfMonth, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Shares(title: String, shares: List<Pair<String, Float>>, tag: String) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        shares.forEach { (name, fraction) ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row { Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Text("${(fraction * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium) }
                ProgressLine(fraction, Modifier.fillMaxWidth().padding(top = 4.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
