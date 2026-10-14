package com.treadz.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.TreadmillIndicator
import com.treadz.console.hrm.DiscoveredStrap
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill

/** Actions of the Devices screen. */
data class DevicesActions(
    val onBack: () -> Unit = {},
    // Treadmill
    val onScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onConnect: (DiscoveredTreadmill) -> Unit = {},
    val onReconnectRemembered: () -> Unit = {},
    val onForgetTreadmill: () -> Unit = {},
    val onEnableBluetooth: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    // Strap
    val onStrapScan: () -> Unit = {},
    val onStrapStopScan: () -> Unit = {},
    val onPairStrap: (DiscoveredStrap) -> Unit = {},
    val onForgetStrap: () -> Unit = {},
    val onOpenResting: () -> Unit = {},
)

/**
 * The two kinds of device, never mixed: the **treadmill** (remembered one, state, connect, search, forget) and the
 * **heart-rate strap** of the active user (state, battery, heart rate, pair, change, forget). Leaving the screen stops
 * the searches.
 */
@Composable
fun DevicesScreen(ui: MainUiState, actions: DevicesActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    DisposableEffect(Unit) {
        onDispose {
            actions.onStopScan()
            actions.onStrapStopScan()
        }
    }
    Column(modifier.fillMaxSize().testTag("devices_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = stringResource(R.string.devices_title), onBack = actions.onBack)
        if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TreadmillSection(ui, actions)
                }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StrapSection(ui, actions)
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).testTag("devices_scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TreadmillSection(ui, actions)
                StrapSection(ui, actions)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Treadmill

@Composable
private fun TreadmillSection(ui: MainUiState, actions: DevicesActions) {
    val connecting = ui.connection is ConnectionState.Connecting
    val connected = ui.connection is ConnectionState.Connected
    val (status, color) = treadmillStatus(ui)
    SettingsSection(stringResource(R.string.devices_treadmill_title), Modifier.testTag("devices_treadmill")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(color, size = 14.dp)
            Text(status, Modifier.weight(1f).testTag("treadmill_state"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (ui.rememberedAddress != null) {
            // Never the address: it means nothing to the user.
            Text(
                ui.rememberedName?.let { stringResource(R.string.devices_remembered, it) } ?: stringResource(R.string.devices_remembered_unnamed),
                Modifier.testTag("treadmill_remembered"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.devices_treadmill_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.treadmillIndicator == TreadmillIndicator.NotFound && ui.rememberedAddress != null) {
            Text(stringResource(R.string.devices_treadmill_gave_up), Modifier.testTag("treadmill_gave_up"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
        }
        if (connecting) {
            Row(Modifier.fillMaxWidth().testTag("connecting_message"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 4.dp)
                Text(stringResource(R.string.connecting_to_message), style = MaterialTheme.typography.titleMedium)
            }
        }
        // One clear message: Bluetooth off replaces every other error (they are consequences of it).
        val problem = ui.problem
        if (!ui.bluetoothEnabled) {
            BluetoothOffPrompt(actions.onEnableBluetooth)
        } else if (problem != null && problem != ConnectionProblem.BluetoothOff) {
            ProblemMessage(problem, MainActions(onRequestPermissions = actions.onRequestPermissions))
        }
        if (ui.rememberedAddress != null && !connected && !connecting) {
            Button(
                onClick = actions.onReconnectRemembered,
                enabled = ui.bluetoothEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("reconnect_button"),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    ui.rememberedName?.let { stringResource(R.string.reconnect_remembered, it) } ?: stringResource(R.string.reconnect_generic),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!connected && !connecting) {
            ScanControls(
                scanning = ui.scanning,
                enabled = ui.bluetoothEnabled,
                searchLabel = stringResource(R.string.scan_button),
                onScan = actions.onScan,
                onStop = actions.onStopScan,
                tag = "scan_button",
                labelTag = "scanning_label",
            )
            if (ui.devices.isEmpty() && ui.scanFinished && !ui.scanning && ui.problem == null && ui.bluetoothEnabled) {
                Text(stringResource(R.string.scan_empty), Modifier.testTag("scan_empty"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.devices.forEach { device ->
                DeviceRow(device.name ?: stringResource(R.string.device_unnamed), device.address, device.rssi, enabled = ui.bluetoothEnabled) { actions.onConnect(device) }
            }
        }
        if (ui.rememberedAddress != null) {
            BigOutlinedButton(
                stringResource(R.string.settings_treadmill_forget),
                actions.onForgetTreadmill,
                Modifier.fillMaxWidth().testTag("devices_forget_treadmill"),
                enabled = ui.canForgetTreadmill,
                destructive = true,
            )
            if (ui.beltActive) {
                Text(stringResource(R.string.settings_belt_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Strap

@Composable
private fun StrapSection(ui: MainUiState, actions: DevicesActions) {
    val strap = ui.strap
    val user = ui.activeUser
    val title = stringResource(R.string.devices_strap_title, user?.displayName() ?: stringResource(R.string.user_none))
    SettingsSection(title, Modifier.testTag("devices_strap")) {
        if (user == null) {
            Text(stringResource(R.string.devices_strap_no_user), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingsSection
        }
        val (status, color) = strapStatus(strap)
        if (strap.paired) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeartIcon(tint = color, size = 20.dp)
                Text(strap.name ?: stringResource(R.string.strap_unnamed), Modifier.weight(1f).testTag("strap_name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            FactRow(stringResource(R.string.devices_strap_state), strapStateText(ui), Modifier.testTag("strap_state"))
            if (strap.connected) {
                strap.batteryPercent?.let { FactRow(stringResource(R.string.devices_strap_battery), stringResource(R.string.devices_battery_value, it), Modifier.testTag("strap_battery")) }
                FactRow(stringResource(R.string.metric_heart_rate), strap.strapBpm?.let { stringResource(R.string.strap_bpm, it) } ?: stringResource(R.string.strap_connected_no_reading), Modifier.testTag("strap_bpm"), valueColor = color)
            }
            val live = strap.heartRate
            if (live.source != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeartRateSourceIcon(live.source)
                    Text(
                        stringResource(if (live.source == HeartRateSourceKind.DirectStrap) R.string.devices_hr_from_strap else R.string.devices_hr_from_console),
                        Modifier.testTag("strap_hr_source"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(stringResource(R.string.devices_strap_none), Modifier.testTag("strap_none"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (strap.problem) {
            StrapProblem.BluetoothOff -> BluetoothOffPrompt(actions.onEnableBluetooth, Modifier.testTag("strap_bluetooth_off"))
            StrapProblem.PermissionMissing -> ProblemMessage(ConnectionProblem.PermissionMissing, MainActions(onRequestPermissions = actions.onRequestPermissions))
            null -> Unit
        }
        if (strap.saveFailed) ErrorText(stringResource(R.string.error_storage), Modifier.testTag("strap_save_error"))
        ScanControls(
            scanning = strap.scanning,
            enabled = strap.problem == null,
            searchLabel = stringResource(if (strap.paired) R.string.devices_strap_change else R.string.devices_strap_pair),
            onScan = actions.onStrapScan,
            onStop = actions.onStrapStopScan,
            tag = "strap_scan_button",
            labelTag = "strap_scanning_label",
        )
        if (strap.scanProblem != null && strap.problem == null) {
            Text(stringResource(R.string.devices_strap_scan_failed), Modifier.testTag("strap_scan_problem"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (strap.devices.isEmpty() && strap.scanFinished && !strap.scanning && strap.scanProblem == null) {
            Text(stringResource(R.string.devices_strap_scan_empty), Modifier.testTag("strap_scan_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        strap.devices.forEach { found ->
            DeviceRow(found.name ?: stringResource(R.string.strap_unnamed), found.address, found.rssi, enabled = true) { actions.onPairStrap(found) }
        }
        if (strap.paired) {
            BigOutlinedButton(stringResource(R.string.devices_strap_forget), actions.onForgetStrap, Modifier.fillMaxWidth().testTag("forget_strap"), destructive = true)
        }
        BigOutlinedButton(stringResource(R.string.home_resting), actions.onOpenResting, Modifier.fillMaxWidth().testTag("devices_open_resting"))
    }
}

@Composable
private fun strapStateText(ui: MainUiState): String {
    val strap = ui.strap
    return when {
        strap.problem == StrapProblem.BluetoothOff -> stringResource(R.string.conn_bluetooth_off)
        strap.problem == StrapProblem.PermissionMissing -> stringResource(R.string.strap_permission_needed)
        strap.connected -> stringResource(R.string.strap_state_connected)
        strap.connection is com.treadz.console.hrm.StrapConnectionState.Connecting -> stringResource(R.string.conn_connecting)
        else -> stringResource(R.string.strap_state_searching)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Shared pieces

@Composable
private fun ScanControls(scanning: Boolean, enabled: Boolean, searchLabel: String, onScan: () -> Unit, onStop: () -> Unit, tag: String, labelTag: String) {
    if (scanning) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 4.dp)
            Text(stringResource(R.string.scan_searching), Modifier.weight(1f).testTag(labelTag), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.scan_stop), style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        OutlinedButton(
            onClick = onScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(searchLabel, style = MaterialTheme.typography.titleLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, rssi: Int, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.device_signal, rssi), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
