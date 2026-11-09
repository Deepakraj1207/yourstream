package dev.yourstream.ui.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A secondary action on a card (favorite, remove, mark watched…). Shown in the card's menu. */
@Immutable
data class CardAction(val label: String, val onClick: () -> Unit)

/**
 * The card menu: a bottom sheet on touch layouts, a focusable dialog on TV (where sheets are not
 * a remote-friendly pattern). Opened by long press (touch), the Menu key (remote), the "more"
 * button (keyboard), or the screen reader's custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardActionsMenu(title: String, actions: List<CardAction>, onDismiss: () -> Unit) {
    if (LocalWindowLayout.current.isTv) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title, maxLines = 2) },
            text = {
                Column(Modifier.testTag("card-menu")) {
                    actions.forEach { a ->
                        TextButton(onClick = { onDismiss(); a.onClick() }, modifier = Modifier.fillMaxWidth().focusHighlight()) { Text(a.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.ui_close)) } },
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).testTag("card-menu")) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                actions.forEach { a ->
                    ListItem(headlineContent = { Text(a.label) }, modifier = Modifier.clickable(role = Role.Button) { onDismiss(); a.onClick() })
                }
            }
        }
    }
}
