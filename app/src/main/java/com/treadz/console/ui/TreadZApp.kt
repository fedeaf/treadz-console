package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.treadz.console.MainUiState
import com.treadz.console.MainViewModel
import com.treadz.console.R
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.RecordingStatus

/** App root: connects the [MainViewModel] with the UI. */
@Composable
fun TreadZRoot(
    viewModel: MainViewModel,
    permissions: PermissionsUi,
    actions: MainActions,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    TreadZContent(
        ui = ui,
        permissions = permissions,
        actions = actions.copy(
            onStart = viewModel::start,
            onScan = viewModel::startScan,
            onStopScan = viewModel::stopScan,
            onConnect = { viewModel.connect(it.address, it.name) },
            onReconnectRemembered = viewModel::reconnectToRemembered,
            onDismissAlert = viewModel::dismissAlert,
            onDismissRecordingNotice = viewModel::dismissRecordingNotice,
            onDarkThemeChange = viewModel::setDarkTheme,
        ),
    )
}

/** Full UI from a state (no ViewModel): this is what the screenshots render. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun TreadZContent(ui: MainUiState, permissions: PermissionsUi, actions: MainActions) {
    TreadZTheme(darkTheme = ui.darkTheme) {
        KeepScreenOn(ui.keepScreenOn)
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                @Suppress("DEPRECATION")
                val sizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
                val compact = sizeClass.widthSizeClass == WindowWidthSizeClass.Compact
                // Two panes only with a wide landscape window (tablet in landscape).
                val wide = !compact && maxWidth > maxHeight
                val sizes = if (wide) {
                    LiveSizes(maxValue = 120.sp, unit = 30.sp, startHeight = 112.dp, startText = 56.sp, gap = 16.dp, unitInline = true)
                } else {
                    LiveSizes(maxValue = 52.sp, unit = 22.sp, startHeight = 88.dp, startText = 34.sp, gap = 12.dp, unitInline = false)
                }

                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TopBar(ui, actions, compact = compact)
                    if (permissions.bluetooth != BluetoothPermission.Granted) {
                        PermissionPane(permissions.bluetooth, actions, Modifier.weight(1f))
                    } else {
                        if (permissions.notificationsDenied) NotificationsDeniedBanner()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (ui.showsLiveData) {
                                LiveData(ui, actions, wide = wide, sizes = sizes)
                            } else {
                                ConnectionPane(ui, actions)
                            }
                        }
                    }
                }
            }

            ui.alert?.let { alert ->
                AlertOverlay(alert = alert, onDismiss = actions.onDismissAlert)
            }
        }
    }
}

@Composable
private fun KeepScreenOn(keepOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun TopBar(ui: MainUiState, actions: MainActions, compact: Boolean) {
    val recording = ui.recording is RecordingStatus.Recording
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppTitle(Modifier.weight(1f))
                ThemeSwitch(ui.darkTheme, actions.onDarkThemeChange)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionChip(ui.connection, Modifier.weight(1f))
                if (recording) RecordingIndicator()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AppTitle()
            ConnectionChip(ui.connection, Modifier.weight(1f))
            if (recording) RecordingIndicator()
            ThemeSwitch(ui.darkTheme, actions.onDarkThemeChange)
        }
    }
}

@Composable
private fun AppTitle(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.app_name),
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
private fun ThemeSwitch(dark: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.theme_dark),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Switch(checked = dark, onCheckedChange = onChange, modifier = Modifier.testTag("theme_switch"))
    }
}

/** Connection state, always visible. */
@Composable
fun ConnectionChip(connection: ConnectionState, modifier: Modifier = Modifier) {
    val unnamed = stringResource(R.string.treadmill_unnamed)
    val (text, color) = when (connection) {
        ConnectionState.Disconnected -> stringResource(R.string.conn_disconnected) to MaterialTheme.colorScheme.error
        is ConnectionState.Connecting -> stringResource(R.string.conn_connecting) to MaterialTheme.extra.warning
        is ConnectionState.Connected ->
            stringResource(R.string.conn_connected, connection.name ?: unnamed) to MaterialTheme.extra.ok
        is ConnectionState.Reconnecting ->
            stringResource(R.string.conn_reconnecting, connection.attempt) to MaterialTheme.extra.warning
    }
    Row(modifier.testTag("connection_chip"), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 14.dp)
        Text(
            text,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
