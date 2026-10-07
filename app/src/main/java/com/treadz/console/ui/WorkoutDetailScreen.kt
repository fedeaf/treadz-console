package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.history.WorkoutDetailUiState
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutSample

data class WorkoutDetailActions(
    val onBack: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onAssign: () -> Unit = {},
    val onAssignTo: (Long) -> Unit = {},
    val onCancelAssign: () -> Unit = {},
)

private const val MAX_CHART_POINTS = 400

@Composable
fun WorkoutDetailScreen(state: WorkoutDetailUiState, actions: WorkoutDetailActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val detail = state.detail
    val locale = currentLocale()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = detail?.let { Format.dateTime(it.summary.startedAtMillis, locale) } ?: stringResource(R.string.detail_title),
            subtitle = if (detail != null) (state.owner?.displayName()?.let { stringResource(R.string.detail_owner, it) } ?: stringResource(R.string.detail_unassigned)) else null,
            onBack = actions.onBack,
        )
        when {
            state.notFound -> Text(
                stringResource(R.string.detail_not_found),
                Modifier.testTag("detail_not_found"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail == null -> Unit
            layout.wide -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.owner == null) UnassignedBanner(actions)
                    Summary(detail)
                    DeleteWorkoutButton(actions)
                }
                Charts(detail, Modifier.weight(1.8f).fillMaxSize())
            }
            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.owner == null) UnassignedBanner(actions)
                Summary(detail)
                Charts(detail, Modifier.fillMaxWidth())
                DeleteWorkoutButton(actions)
            }
        }
        if (state.failed) ErrorText(stringResource(R.string.error_storage))
    }
    if (state.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.workout_delete_title),
            text = stringResource(R.string.workout_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
    if (state.assigning) AssignDialog(state.users, onAssignTo = actions.onAssignTo, onDismiss = actions.onCancelAssign)
}

@Composable
private fun Summary(detail: WorkoutDetail) {
    val locale = currentLocale()
    val summary = detail.summary
    val stats = summary.stats
    val hrUnit = stringResource(R.string.unit_heart_rate)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (summary.isInProgress) {
            Tag(stringResource(R.string.history_in_progress), MaterialTheme.extra.ok, Modifier.testTag("in_progress_tag"))
        } else {
            summary.endReason?.let { Tag(it.label(), MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (summary.isInProgress) {
        Text(stringResource(R.string.detail_in_progress_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_duration), Format.duration(stats.durationSeconds, locale), Modifier.weight(1f))
        StatTile(stringResource(R.string.stat_distance), Format.distanceKm(stats.distanceMeters, locale), Modifier.weight(1f), unit = stringResource(R.string.unit_distance))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_calories), Format.calories(stats.calories, locale), Modifier.weight(1f), unit = stringResource(R.string.unit_calories))
        StatTile(stringResource(R.string.stat_avg_speed), Format.speed(stats.averageSpeedKmh, locale), Modifier.weight(1f), unit = stringResource(R.string.unit_speed))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_avg_hr), Format.heartRate(stats.averageHeartRateBpm, locale), Modifier.weight(1f), unit = if (stats.averageHeartRateBpm != null) hrUnit else null)
        StatTile(stringResource(R.string.stat_max_hr), Format.heartRate(stats.maxHeartRateBpm, locale), Modifier.weight(1f), unit = if (stats.maxHeartRateBpm != null) hrUnit else null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_max_speed), Format.speed(stats.maxSpeedKmh, locale), Modifier.weight(1f), unit = stringResource(R.string.unit_speed))
        StatTile(stringResource(R.string.stat_max_incline), Format.incline(stats.maxInclinePercent, locale), Modifier.weight(1f), unit = stringResource(R.string.unit_incline))
    }
}

@Composable
private fun DeleteWorkoutButton(actions: WorkoutDetailActions) {
    BigOutlinedButton(stringResource(R.string.workout_delete), actions.onDelete, Modifier.fillMaxWidth().testTag("delete_workout_button"), destructive = true)
}

@Composable
private fun Charts(detail: WorkoutDetail, modifier: Modifier) {
    val locale = currentLocale()
    val samples = detail.samples
    val xLabel: (Double) -> String = { Format.duration(it.toInt(), locale) }
    val layout = LocalLayout.current
    val speedText = stringResource(R.string.chart_speed)
    val inclineText = stringResource(R.string.chart_incline)
    val hrText = stringResource(R.string.chart_heart_rate)
    // Same height for the three charts: each takes a third of the space (tablet) or a fixed share (phone, scrolling).
    @Composable
    fun ColumnScope.chart(title: String, tag: String, color: Color, points: List<ChartPoint>, yLabel: (Double) -> String, yFloor: Double?, empty: String) {
        Panel((if (layout.wide) Modifier.weight(1f) else Modifier.height(190.dp)).fillMaxWidth().testTag(tag)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LineChart(
                    points = ChartMath.downsample(points, MAX_CHART_POINTS),
                    color = color,
                    yLabel = yLabel,
                    xLabel = xLabel,
                    description = title,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
                    yFloor = yFloor,
                    emptyText = empty,
                )
            }
        }
    }
    val start = samples.firstOrNull()?.timestampMillis ?: 0L
    fun series(value: (WorkoutSample) -> Double?): List<ChartPoint> =
        samples.mapNotNull { s -> value(s)?.let { ChartPoint((s.timestampMillis - start) / 1000.0, it) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chart(speedText, "chart_speed", MaterialTheme.colorScheme.primary, series { it.speedKmh }, { Format.speed(it, locale) }, 0.0, stringResource(R.string.chart_empty))
        chart(inclineText, "chart_incline", MaterialTheme.colorScheme.secondary, series { it.inclinePercent }, { Format.incline(it, locale) }, 0.0, stringResource(R.string.chart_empty))
        chart(
            hrText, "chart_heart_rate", MaterialTheme.colorScheme.error,
            series { it.heartRateBpm?.toDouble() }, { Format.whole(it.toInt(), locale) }, null, stringResource(R.string.chart_no_hr),
        )
    }
}

/** A workout nobody owns: say so at the top, where it is noticed, with the way out. */
@Composable
private fun UnassignedBanner(actions: WorkoutDetailActions) {
    Panel(Modifier.fillMaxWidth().testTag("unassigned_banner"), borderColor = MaterialTheme.extra.warning) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.detail_unassigned_note),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            RowButton(stringResource(R.string.workout_assign_short), actions.onAssign, Modifier.testTag("assign_banner_button"))
        }
    }
}
