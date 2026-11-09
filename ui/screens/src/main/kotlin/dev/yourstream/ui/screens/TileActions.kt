package dev.yourstream.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.ui.core.CardAction
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.engine.WatchIntelligence
import javax.inject.Inject
import javax.inject.Singleton

/** Secondary actions on a card; executed by the domain repositories. */
sealed interface TileAction {
    data class SetFavorite(val itemId: Long, val favorite: Boolean) : TileAction
    data class SetWatched(val itemId: Long, val watched: Boolean) : TileAction
    data class Dismiss(val itemId: Long) : TileAction
}

/** Runs [TileAction]s through the existing use cases (lists refresh by themselves via Room). */
@Singleton
class TileActionHandler @Inject constructor(
    private val media: MediaRepository,
    private val history: WatchHistoryRepository,
    private val watch: WatchIntelligence,
) {
    suspend fun run(action: TileAction) {
        when (action) {
            is TileAction.SetFavorite -> media.setFavorite(action.itemId, action.favorite)
            is TileAction.SetWatched -> history.markWatched(PlaybackTarget(action.itemId), action.watched)
            is TileAction.Dismiss -> watch.dismiss(action.itemId)
        }
    }
}

/** The menu entries a tile offers (localised here, in the UI). */
@Composable
fun tileActions(onAction: (TileAction) -> Unit, onOpen: (OpenTarget) -> Unit): (Tile) -> List<CardAction> {
    val details = stringResource(R.string.action_details)
    val fav = stringResource(R.string.action_favorite)
    val unfav = stringResource(R.string.action_unfavorite)
    val watched = stringResource(R.string.action_mark_watched)
    val unwatched = stringResource(R.string.action_mark_unwatched)
    val remove = stringResource(R.string.action_remove_continue)
    return { t ->
        val id = t.itemId
        if (id == null) emptyList() else buildList {
            if (t.open !is OpenTarget.Details) add(CardAction(details) { onOpen(OpenTarget.Details(id)) })
            t.isFavorite?.let { f -> add(CardAction(if (f) unfav else fav) { onAction(TileAction.SetFavorite(id, !f)) }) }
            t.watched?.let { w -> add(CardAction(if (w) unwatched else watched) { onAction(TileAction.SetWatched(id, !w)) }) }
            if (t.dismissible) add(CardAction(remove) { onAction(TileAction.Dismiss(id)) })
        }
    }
}

/** Localised caption for Continue Watching cards. */
@Composable
fun tileCard(): (Tile) -> dev.yourstream.ui.core.MediaCardModel {
    val next = stringResource(R.string.caption_next_episode)
    val new = stringResource(R.string.caption_new_episode)
    val start = stringResource(R.string.caption_start)
    val resume = stringResource(R.string.action_resume)
    val left = stringResource(R.string.caption_minutes_left)
    return { t ->
        when (val c = t.caption) {
            null -> t.card
            is TileCaption.MinutesLeft -> t.card.copy(caption = left.format(c.minutes))
            TileCaption.NextEpisode -> t.card.copy(caption = next)
            TileCaption.NewEpisode -> t.card.copy(caption = new, badge = new)
            TileCaption.Start -> t.card.copy(caption = start)
            is TileCaption.Reason -> t.card.copy(caption = c.text)
            TileCaption.Resume -> t.card.copy(caption = resume)
        }
    }
}
