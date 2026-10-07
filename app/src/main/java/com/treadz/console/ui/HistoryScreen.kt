package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.history.HistoryTab
import com.treadz.console.history.HistoryUiState
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutSummary

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutCard(
    workout: WorkoutSummary,
    unassigned: Boolean,
    onOpen: () -> Unit,
    onAssign: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = currentLocale()
    val stats = workout.stats
    Panel(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("workout_${workout.id}"),
        borderColor = if (workout.isInProgress) MaterialTheme.extra.ok else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Format.dateTime(workout.startedAtMillis, locale),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (workout.isInProgress) {
                    Tag(stringResource(R.string.history_in_progress), MaterialTheme.extra.ok, Modifier.testTag("in_progress_tag"))
                } else {
                    workout.endReason?.let { reason ->
                        val color = if (reason == com.treadz.console.userdata.WorkoutEndReason.Stopped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.extra.warning
                        Tag(reason.label(), color)
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                Stat(stringResource(R.string.stat_duration), Format.duration(stats.durationSeconds, locale))
                Stat(stringResource(R.string.stat_distance), "${Format.distanceKm(stats.distanceMeters, locale)} ${stringResource(R.string.unit_distance)}")
                Stat(stringResource(R.string.stat_calories), "${Format.calories(stats.calories, locale)} ${stringResource(R.string.unit_calories)}")
                Stat(stringResource(R.string.stat_avg_speed_short), "${Format.speed(stats.averageSpeedKmh, locale)} ${stringResource(R.string.unit_speed)}")
                Stat(stringResource(R.string.stat_avg_hr_short), stats.averageHeartRateBpm?.let { "${Format.whole(it, locale)} ${stringResource(R.string.unit_heart_rate)}" } ?: Format.NO_VALUE)
                Stat(stringResource(R.string.stat_max_hr_short), stats.maxHeartRateBpm?.let { "${Format.whole(it, locale)} ${stringResource(R.string.unit_heart_rate)}" } ?: Format.NO_VALUE)
            }
            if (unassigned) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    RowButton(stringResource(R.string.action_delete), onDelete, Modifier.testTag("delete_workout_${workout.id}"), destructive = true)
                    RowButton(stringResource(R.string.workout_assign), onAssign, Modifier.testTag("assign_workout_${workout.id}"))
                }
            }
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
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
