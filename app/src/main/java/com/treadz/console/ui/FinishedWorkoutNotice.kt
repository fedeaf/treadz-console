package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.training.FinishedWorkout

/** After a workout without a program: what was recorded, and "Done" or "Discard". */
@Composable
fun FinishedWorkoutNotice(ui: MainUiState, actions: MainActions) {
    val workout = ui.finishedWorkout ?: return
    var confirming by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("finished_workout").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.finished_workout_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(finishedWorkoutFigures(workout), style = MaterialTheme.typography.bodyMedium)
            // The split appears when the data layer has analysed the workout, a few seconds after the end.
            ui.finishedFuel?.let { FuelEndLine(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { confirming = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("discard_workout_button")) {
                    Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = actions.onKeepWorkout, modifier = Modifier.heightIn(min = 48.dp).testTag("keep_workout_button")) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
    if (confirming) {
        DiscardWorkoutDialog(
            onConfirm = {
                confirming = false
                actions.onDiscardWorkout()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** Confirmation of "Discard": the workout is deleted and never uploaded. */
@Composable
fun DiscardWorkoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.discard_workout_title),
        text = stringResource(R.string.discard_workout_body),
        confirmText = stringResource(R.string.action_discard),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun finishedWorkoutFigures(workout: FinishedWorkout): String =
    stringResource(
        R.string.finished_workout_figures,
        Format.duration(workout.durationSeconds),
        Format.distanceKm(workout.distanceMeters),
    )
