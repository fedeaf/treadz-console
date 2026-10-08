package com.treadz.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.treadmill.ConnectionProblem

/** Translated text for a connection problem. The backend's exception and reason texts are never shown. */
@Composable
fun problemText(problem: ConnectionProblem): String = stringResource(
    when (problem) {
        ConnectionProblem.BluetoothOff -> R.string.bluetooth_off_title
        ConnectionProblem.PermissionMissing -> R.string.problem_permission
        ConnectionProblem.DeviceNotFound -> R.string.problem_not_found
        ConnectionProblem.Other -> R.string.problem_other
    },
)

/** The single prompt shown while Bluetooth is off, with the button that asks Android to turn it on. */
@Composable
fun BluetoothOffPrompt(onEnable: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("bluetooth_off_prompt").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(2.dp, MaterialTheme.extra.warning),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.bluetooth_off_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.extra.warning,
            )
            Text(stringResource(R.string.bluetooth_off_body), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("bluetooth_enable_button"),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(stringResource(R.string.bluetooth_turn_on), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Why a scan or a connection failed, in one translated line (plus the action that fixes it, if there is one).
 * Bluetooth off is the [BluetoothOffPrompt], never this.
 */
@Composable
fun ProblemMessage(problem: ConnectionProblem, actions: MainActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("connect_problem"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            problemText(problem),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (problem == ConnectionProblem.PermissionMissing) {
            OutlinedButton(
                onClick = actions.onRequestPermissions,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("problem_permission_button"),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(stringResource(R.string.perm_grant), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Banner of the live screen while the app reconnects. It always offers "Stop reconnecting" (the way back to scanning); the
 * explanation and the button grow when the treadmill is not found or Bluetooth is off. It sits at the top, far from the
 * STOP area, and is styled as a secondary action so that it is never taken for STOP.
 */
@Composable
fun ReconnectingBanner(ui: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val waitingForBluetooth = ui.waitingForBluetooth
    val stuck = ui.reconnectStuck
    val (title, body) = when {
        waitingForBluetooth -> R.string.reconnecting_bluetooth_off_title to R.string.reconnecting_bluetooth_off_body
        stuck -> R.string.reconnecting_stuck_title to R.string.reconnecting_stuck_body
        else -> null to R.string.reconnecting_banner
    }
    val prominent = stuck || waitingForBluetooth
    val wide = LocalLayout.current.wide
    val warning = MaterialTheme.extra.warning
    val onWarning = MaterialTheme.extra.onWarning

    @Composable
    fun texts(modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
        Text(
            stringResource(body),
            style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Bold,
        )
    }

    @Composable
    fun buttons(modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (waitingForBluetooth) {
            Button(
                onClick = actions.onEnableBluetooth,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("bluetooth_enable_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = onWarning, contentColor = warning),
            ) {
                Text(stringResource(R.string.bluetooth_turn_on), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (prominent && !waitingForBluetooth) {
            // Filled and big: this is what the person should do now.
            Button(
                onClick = actions.onStopReconnecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("stop_reconnecting_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = onWarning, contentColor = warning),
            ) {
                Text(stringResource(R.string.reconnect_stop_and_search), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = actions.onStopReconnecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop_reconnecting_button"),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, onWarning),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = onWarning),
            ) {
                Text(stringResource(R.string.reconnect_stop), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("reconnecting_banner").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = warning,
        contentColor = onWarning,
    ) {
        if (wide) {
            // Tablet: text on the left, the action on the right, so the banner does not take the height of the controls.
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                texts(Modifier.weight(1f))
                buttons(Modifier.width(380.dp))
            }
        } else {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                texts(Modifier)
                buttons(Modifier)
            }
        }
    }
}
