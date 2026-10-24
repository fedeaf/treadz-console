package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.history.HistoryTab
import com.treadz.console.history.HistoryUiState
import com.treadz.console.userdata.UserProfile

data class HistoryActions(
    val onBack: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onTab: (HistoryTab) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onAssign: (Long) -> Unit = {},
    val onAssignTo: (Long) -> Unit = {},
    val onCancelAssign: () -> Unit = {},
)

@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val tab = state.visibleTab
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(R.string.history_title),
            subtitle = state.user?.displayName(),
            onBack = actions.onBack,
        )
        if (state.unassigned.isNotEmpty()) {
            PrimaryTabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.testTag("history_tabs"),
            ) {
                Tab(
                    selected = tab == HistoryTab.Mine,
                    onClick = { actions.onTab(HistoryTab.Mine) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("tab_mine"),
                ) { Text(stringResource(R.string.history_tab_mine, state.workouts.size), style = MaterialTheme.typography.titleMedium) }
                Tab(
                    selected = tab == HistoryTab.Unassigned,
                    onClick = { actions.onTab(HistoryTab.Unassigned) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("tab_unassigned"),
                ) { Text(stringResource(R.string.history_tab_unassigned, state.unassigned.size), style = MaterialTheme.typography.titleMedium) }
            }
        }
        if (tab == HistoryTab.Unassigned) {
            Text(
                stringResource(R.string.history_unassigned_note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("unassigned_note"),
            )
        }
        val list = if (tab == HistoryTab.Mine) state.workouts else state.unassigned
        if (list.isEmpty()) {
            Text(
                stringResource(if (state.user == null && state.loaded) R.string.no_user_chosen else R.string.history_empty),
                Modifier.testTag("history_empty"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyVerticalGrid(
                columns = if (layout.wide) GridCells.Adaptive(minSize = 480.dp) else GridCells.Fixed(1),
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("history_list"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { workout ->
                    WorkoutCard(
                        workout = workout,
                        unassigned = tab == HistoryTab.Unassigned,
                        pendingUpload = workout.id in state.pendingUploadIds,
                        couchSession = state.couchSessions[workout.id],
                        onOpen = { actions.onOpen(workout.id) },
                        onAssign = { actions.onAssign(workout.id) },
                        onDelete = { actions.onDelete(workout.id) },
                    )
                }
            }
        }
        if (state.failed) ErrorText(stringResource(R.string.error_storage))
    }
    state.confirmingDeleteId?.let {
        ConfirmDialog(
            title = stringResource(R.string.workout_delete_title),
            text = stringResource(R.string.workout_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
    if (state.assigningId != null) {
        AssignDialog(state.users, onAssignTo = actions.onAssignTo, onDismiss = actions.onCancelAssign)
    }
}

/** Who should this workout belong to. */
@Composable
fun AssignDialog(users: List<UserProfile>, onAssignTo: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("assign_dialog"),
        title = { Text(stringResource(R.string.assign_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                users.forEach { user ->
                    BigOutlinedButton(user.displayName(), { onAssignTo(user.id) }, Modifier.fillMaxWidth().testTag("assign_to_${user.id}"))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
