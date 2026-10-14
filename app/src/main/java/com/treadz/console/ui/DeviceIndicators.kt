package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.TreadmillIndicator
import com.treadz.console.devices.StrapUiState
import com.treadz.console.hrm.StrapConnectionState
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.treadmill.ConnectionState

/** The word and colour of the treadmill's state. */
@Composable
fun treadmillStatus(ui: MainUiState): Pair<String, Color> {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val warning = MaterialTheme.extra.warning
    return when (ui.treadmillIndicator) {
        TreadmillIndicator.Connected -> (ui.connectedName ?: stringResource(R.string.treadmill_unnamed)) to MaterialTheme.extra.ok
        TreadmillIndicator.Connecting -> stringResource(R.string.conn_connecting) to warning
        TreadmillIndicator.Reconnecting ->
            stringResource(R.string.conn_reconnecting, (ui.connection as? ConnectionState.Reconnecting)?.attempt ?: 1) to warning
        TreadmillIndicator.WaitingForBluetooth -> stringResource(R.string.conn_waiting_bluetooth) to warning
        TreadmillIndicator.BluetoothOff -> stringResource(R.string.conn_bluetooth_off) to warning
        TreadmillIndicator.Searching -> stringResource(R.string.conn_searching) to warning
        TreadmillIndicator.NotFound -> stringResource(R.string.conn_not_found) to warning
        TreadmillIndicator.NotSetUp -> stringResource(R.string.conn_not_set_up) to neutral
        TreadmillIndicator.NotConnected -> stringResource(R.string.conn_disconnected) to neutral
    }
}

/** The word and colour of the strap's state: the live heart rate when connected. */
@Composable
fun strapStatus(strap: StrapUiState): Pair<String, Color> {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val warning = MaterialTheme.extra.warning
    return when {
        !strap.paired -> stringResource(R.string.strap_none) to neutral
        strap.problem == StrapProblem.BluetoothOff -> stringResource(R.string.conn_bluetooth_off) to warning
        strap.problem == StrapProblem.PermissionMissing -> stringResource(R.string.strap_permission_needed) to warning
        strap.connected -> (strap.strapBpm?.let { stringResource(R.string.strap_bpm, it) } ?: stringResource(R.string.strap_connected_no_reading)) to MaterialTheme.extra.ok
        strap.connection is StrapConnectionState.Connecting -> stringResource(R.string.conn_connecting) to warning
        else -> stringResource(R.string.conn_searching) to neutral
    }
}

/** The two small indicators of the top bar: the treadmill and the strap. Tapping either opens Devices. */
@Composable
fun DeviceChips(ui: MainUiState, onOpenDevices: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val (treadmillText, treadmillColor) = treadmillStatus(ui)
        DeviceChip(
            label = stringResource(R.string.device_treadmill),
            status = treadmillText,
            color = treadmillColor,
            tag = "treadmill_chip",
            onClick = onOpenDevices,
            modifier = Modifier.weight(1f, fill = false),
        ) { StatusDot(treadmillColor, size = 12.dp) }
        val (strapText, strapColor) = strapStatus(ui.strap)
        DeviceChip(
            label = stringResource(R.string.device_strap),
            status = strapText,
            color = strapColor,
            tag = "strap_chip",
            onClick = onOpenDevices,
            modifier = Modifier.weight(1f, fill = false),
        ) { HeartIcon(tint = strapColor, size = 16.dp) }
    }
}

@Composable
private fun DeviceChip(
    label: String,
    status: String,
    color: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Panel(modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Column(Modifier.weight(1f, fill = false)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    status,
                    Modifier.testTag("${tag}_status"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    color = if (color == MaterialTheme.colorScheme.onSurfaceVariant) MaterialTheme.colorScheme.onSurface else color,
                )
            }
        }
    }
}
