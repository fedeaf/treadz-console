package com.treadz.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill

/** Connection screen: reconnect to the last treadmill, scan and pick one. */
@Composable
fun ConnectionPane(ui: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val connecting = ui.connection is ConnectionState.Connecting
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.connect_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.connect_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (connecting) {
                Row(
                    Modifier.fillMaxWidth().testTag("connecting_message"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 4.dp)
                    Text(stringResource(R.string.connecting_to_message), style = MaterialTheme.typography.titleMedium)
                }
            }

            val failure = ui.connectFailure
            if (failure != null) {
                Text(
                    connectFailureMessage(failure),
                    modifier = Modifier.testTag("connect_failure"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val scanFailure = ui.scanFailure
            if (scanFailure != null) {
                Text(
                    stringResource(R.string.scan_failed, scanFailure),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (ui.rememberedAddress != null && !connecting) {
                Button(
                    onClick = actions.onReconnectRemembered,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("reconnect_button"),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        stringResource(R.string.reconnect_remembered, ui.rememberedName ?: ui.rememberedAddress),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (!connecting) ScanControls(ui, actions, enabled = true)

            if (ui.devices.isEmpty() && ui.scanFinished && !ui.scanning && !connecting) {
                Text(
                    stringResource(R.string.scan_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.devices.forEach { device ->
                DeviceRow(device, enabled = !connecting, onClick = { actions.onConnect(device) })
            }
        }
    }
}

@Composable
private fun ScanControls(ui: MainUiState, actions: MainActions, enabled: Boolean) {
    if (ui.scanning) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 4.dp)
            Text(
                stringResource(R.string.scan_searching),
                modifier = Modifier.weight(1f).testTag("scanning_label"),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = actions.onStopScan, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.scan_stop), style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        OutlinedButton(
            onClick = actions.onScan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("scan_button"),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(stringResource(R.string.scan_button), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredTreadmill, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: stringResource(R.string.device_unnamed),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.device_signal, device.rssi),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun connectFailureMessage(result: CommandResult): String = when (result) {
    CommandResult.Success -> ""
    CommandResult.NotConnected -> stringResource(R.string.connect_failed_not_connected)
    is CommandResult.Failure -> stringResource(R.string.connect_failed, failureText(result.code))
}
