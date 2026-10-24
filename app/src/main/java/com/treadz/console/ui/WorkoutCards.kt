package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.history.ProgramSessions
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSummary

/**
 * One workout as the History screen lists it: when, what it was (program, generated session, fitness test), how it ended when
 * that is not the normal end, and its figures. The sessions each program shows inside itself use the same rows. With
 * [unassigned] it offers to delete the workout or give it to a user.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutCard(
    workout: WorkoutSummary,
    unassigned: Boolean,
    pendingUpload: Boolean,
    couchSession: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onAssign: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val locale = currentLocale()
    val stats = workout.stats
    Panel(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("workout_${workout.id}"),
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
                val generatorId = workout.generatorId
                if (generatorId != null) {
                    // A generated session is known by its generator ("Couch to 5K · session 7"), not by the name it was saved with.
                    Tag(generatorTagText(generatorId, couchSession), MaterialTheme.colorScheme.secondary, Modifier.testTag("generator_tag_${workout.id}"))
                } else if (workout.programId != null) {
                    Tag(programDisplayName(workout.programId, workout.programName), MaterialTheme.colorScheme.secondary, Modifier.testTag("program_tag_${workout.id}"))
                }
                if (workout.fitnessTestId != null) {
                    Tag(stringResource(R.string.history_fitness_test), MaterialTheme.colorScheme.secondary, Modifier.testTag("test_tag_${workout.id}"))
                }
                if (pendingUpload) PendingUploadMark(Modifier.testTag("pending_upload_${workout.id}"))
                if (workout.isInProgress) {
                    Tag(stringResource(R.string.history_in_progress), MaterialTheme.extra.ok, Modifier.testTag("in_progress_tag"))
                } else {
                    // The normal end ("Stopped") is not worth a tag: only what went differently is.
                    workout.endReason?.takeIf { it != WorkoutEndReason.Stopped }?.let { reason -> Tag(reason.label(), MaterialTheme.extra.warning) }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                Stat(stringResource(R.string.stat_duration), Format.duration(stats.durationSeconds))
                Stat(stringResource(R.string.stat_distance), "${Format.distanceKm(stats.distanceMeters)} ${stringResource(R.string.unit_distance)}")
                Stat(stringResource(R.string.stat_calories), "${Format.calories(stats.calories)} ${stringResource(R.string.unit_calories)}")
                Stat(stringResource(R.string.stat_avg_speed_short), "${Format.speed(stats.averageSpeedKmh)} ${stringResource(R.string.unit_speed)}")
                Stat(stringResource(R.string.stat_avg_hr_short), stats.averageHeartRateBpm?.let { "${Format.whole(it)} ${stringResource(R.string.unit_heart_rate)}" } ?: Format.NO_VALUE)
                Stat(stringResource(R.string.stat_max_hr_short), stats.maxHeartRateBpm?.let { "${Format.whole(it)} ${stringResource(R.string.unit_heart_rate)}" } ?: Format.NO_VALUE)
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

/**
 * The sessions of one program (or generator) inside its own screen: the same rows as the History screen, newest first, each
 * opening the workout. Says so when there is none.
 */
@Composable
fun ProgramSessionsSection(sessions: ProgramSessions, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("program_sessions"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.program_sessions_title, sessions.workouts.size))
        if (sessions.workouts.isEmpty()) {
            Text(stringResource(R.string.program_sessions_empty), Modifier.testTag("program_sessions_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sessions.workouts.forEach { workout ->
            WorkoutCard(workout, unassigned = false, pendingUpload = false, couchSession = sessions.couchSessions[workout.id], onOpen = { onOpen(workout.id) })
        }
    }
}

/** Small "not uploaded yet" mark of a workout (an arrow pointing up, with text: the meaning never relies on the icon). */
@Composable
private fun PendingUploadMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(size.width / 2, size.height), Offset(size.width / 2, stroke), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width / 2, stroke), Offset(size.width * 0.15f, size.height * 0.45f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width / 2, stroke), Offset(size.width * 0.85f, size.height * 0.45f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        Text(stringResource(R.string.history_pending_upload), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}
