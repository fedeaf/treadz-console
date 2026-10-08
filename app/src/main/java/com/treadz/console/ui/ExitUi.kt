package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.lifecycle.ExitPrompt

/** What the Back button on the root screen shows: the exit dialog, or the "press STOP" message. */
@Composable
fun ExitPromptHost(prompt: ExitPrompt?, onExit: () -> Unit, onKeepInBackground: () -> Unit, onDismiss: () -> Unit) {
    if (prompt is ExitPrompt.Confirm) {
        ExitDialog(prompt.connected, onExit, onKeepInBackground, onDismiss)
    }
}

/** The message that replaces the exit dialog while the belt is moving. It does not block the screen: STOP stays reachable. */
@Composable
fun BeltMovingNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Panel(
        modifier.fillMaxWidth().testTag("exit_belt_moving").semantics { liveRegion = LiveRegionMode.Assertive },
        borderColor = MaterialTheme.extra.alert,
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.exit_belt_moving),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("exit_belt_moving_ok")) {
                Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * "Exit TreadZ?" with three big answers stacked: Exit, Keep in background and Cancel. [connected] adds that exiting
 * disconnects from the treadmill.
 */
@Composable
fun ExitDialog(connected: Boolean, onExit: () -> Unit, onKeepInBackground: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("exit_dialog"),
        title = { Text(stringResource(R.string.exit_title), fontWeight = FontWeight.Bold) },
        text = if (connected) {
            { Text(stringResource(R.string.exit_body_connected), style = MaterialTheme.typography.bodyLarge) }
        } else {
            null
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("exit_confirm_button"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.exit_confirm), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onKeepInBackground,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("exit_keep_button"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.exit_keep_background), style = MaterialTheme.typography.titleMedium)
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("exit_cancel_button"),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    )
}
