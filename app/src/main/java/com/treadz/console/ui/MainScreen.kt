package com.treadz.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.userdata.UserProfile

/** Main screen: top bar (user, weight, history, connection), then the connection pane or the live data. */
@Composable
fun MainScreen(ui: MainUiState, permissions: PermissionsUi, actions: MainActions) {
    val layout = LocalLayout.current
    val compact = layout.compact
    val wide = layout.wide
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
            if (ui.activeUser?.isGeneric == true && ui.canSwitchUser) GenericUserBanner(actions)
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

@Composable
private fun TopBar(ui: MainUiState, actions: MainActions, compact: Boolean) {
    val recording = ui.recording is RecordingStatus.Recording
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppTitle(Modifier.weight(1f))
                ThemeSwitch(ui.darkTheme, actions.onDarkThemeChange)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserChip(ui.activeUser, ui.canSwitchUser, actions.onOpenUsers, Modifier.weight(1f))
                NavButtons(ui, actions)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionChip(ui.connection, Modifier.weight(1f))
                if (recording) RecordingIndicator()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            AppTitle()
            UserChip(ui.activeUser, ui.canSwitchUser, actions.onOpenUsers)
            NavButtons(ui, actions)
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

/** Who is training; tapping it opens the user selection (not while the belt is running). */
@Composable
private fun UserChip(user: UserProfile?, canSwitch: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = user?.displayName() ?: stringResource(R.string.user_none)
    val accent = if (user == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Panel(
        modifier
            .alpha(if (canSwitch) 1f else 0.6f)
            .clickable(enabled = canSwitch, role = Role.Button, onClick = onClick)
            .testTag("user_chip"),
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(34.dp).background(accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    stringResource(if (canSwitch) R.string.user_chip_change else R.string.user_chip_locked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NavButtons(ui: MainUiState, actions: MainActions) {
    val padding = PaddingValues(horizontal = 14.dp)
    RowButton(stringResource(R.string.main_weight), actions.onOpenWeight, Modifier.testTag("weight_button"), contentPadding = padding)
    BadgedBox(
        badge = {
            if (ui.unassignedWorkoutCount > 0) {
                Badge(Modifier.testTag("unassigned_badge")) { Text(ui.unassignedWorkoutCount.toString()) }
            }
        },
    ) {
        RowButton(stringResource(R.string.main_history), actions.onOpenHistory, Modifier.testTag("history_button"), contentPadding = padding)
    }
}

/**
 * The generic user has made-up data: invite the person to create their own profile. A phone keeps it to one line so
 * the live figures keep their room.
 */
@Composable
private fun GenericUserBanner(actions: MainActions) {
    val compact = LocalLayout.current.compact
    Panel(
        Modifier.fillMaxWidth().testTag("generic_banner"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        borderColor = MaterialTheme.extra.warning,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (compact) {
                Text(
                    stringResource(R.string.generic_banner_short),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.extra.warning,
                    maxLines = 1,
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.generic_banner_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
                    Text(stringResource(R.string.generic_banner_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            RowButton(stringResource(R.string.generic_banner_action), actions.onPersonalize, Modifier.testTag("personalize_button"))
        }
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
